package com.chatbot.service;

import com.chatbot.entity.Conversation;
import com.chatbot.repository.ConversationRepository;
import com.chatbot.tool.CalculatorTool;
import com.chatbot.tool.DateTimeTool;
import com.chatbot.tool.WeatherTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Hybrid Search(벡터 + FTS) 기반 RAG 채팅 서비스
 * Spring AI를 사용하여 Anthropic Claude API와 통신하고,
 * 벡터 유사도 검색과 PostgreSQL Full Text Search를 병렬로 실행한 뒤
 * Reciprocal Rank Fusion(RRF)으로 결과를 병합하여 컨텍스트로 제공합니다.
 * WeatherTool, DateTimeTool, CalculatorTool을 통해 AI가 필요 시 외부 정보를 조회하거나 계산을 수행할 수 있습니다.
 * 대화 내역은 PostgreSQL DB에 저장됩니다.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // RAG 시스템 프롬프트: 컨텍스트를 우선 사용하도록 지시
    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant. Answer in the same language as the user's message.

            If the following context is provided, use it as the primary source to answer the question.
            If the context is not relevant to the question, answer based on your general knowledge.

            Context:
            %s
            """;

    // RRF 최소 점수 임계값: 관련성 낮은 문서 필터링
    // - 양쪽 1위: 1/61 + 1/61 ≈ 0.033 → 통과
    // - 한쪽 1위: 1/61 ≈ 0.016 → 임계값 미만 제외
    // - 0.018 기준: 한쪽에서 상위권이거나 양쪽에 모두 등장한 문서만 통과
    private static final double MIN_RRF_SCORE = 0.018;

    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WeatherTool weatherTool;
    private final DateTimeTool dateTimeTool;
    private final CalculatorTool calculatorTool;

    /**
     * 소스 파일 정보 레코드
     * @param id 문서 ID
     * @param fileName 파일명
     */
    public record Source(String id, String fileName) {}

    /**
     * 채팅 결과 레코드
     * @param sources 참조한 소스 파일 목록 (문서 ID + 파일명)
     * @param stream 스트리밍 응답 Flux
     */
    public record ChatResult(List<Source> sources, Flux<String> stream) {}

    /**
     * 생성자: ChatClient, Repository, VectorStore, JdbcTemplate, Tools 주입
     * @param anthropicChatModel Anthropic Claude 모델
     * @param conversationRepository 대화 내역 저장소
     * @param vectorStore 벡터 임베딩 저장소 (RAG용)
     * @param jdbcTemplate FTS 쿼리용 JDBC 템플릿
     * @param weatherTool 날씨 조회 도구 (AI가 필요 시 자동 호출)
     * @param dateTimeTool 날짜/시간 조회 도구 (AI가 필요 시 자동 호출)
     * @param calculatorTool 계산기 도구 (AI가 필요 시 자동 호출)
     */
    public ChatService(AnthropicChatModel anthropicChatModel,
                       ConversationRepository conversationRepository,
                       VectorStore vectorStore,
                       JdbcTemplate jdbcTemplate,
                       WeatherTool weatherTool,
                       DateTimeTool dateTimeTool,
                       CalculatorTool calculatorTool) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
        this.conversationRepository = conversationRepository;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.weatherTool = weatherTool;
        this.dateTimeTool = dateTimeTool;
        this.calculatorTool = calculatorTool;
    }

    /**
     * Hybrid Search(벡터 + FTS) + Tool 기반 SSE 스트리밍 채팅 처리
     *
     * @param sessionId   세션 ID (대화 구분용)
     * @param userMessage 사용자 메시지
     * @return ChatResult 소스 파일 목록과 스트리밍 응답
     *
     * 처리 흐름:
     * 1. 벡터 검색(코사인 유사도)과 FTS 검색(키워드 매칭)을 CompletableFuture로 병렬 실행
     * 2. RRF(Reciprocal Rank Fusion)로 두 결과를 순위 기반으로 병합, MIN_RRF_SCORE 미만 제외
     * 3. 상위 5개 문서의 소스 파일명 추출 (중복 제거)
     * 4. 청크 텍스트를 컨텍스트로 조합
     * 5. 시스템 프롬프트에 컨텍스트 삽입
     * 6. DB에서 대화 이력 조회 및 현재 메시지 추가
     * 7. ChatClient로 스트리밍 요청 (Tools 포함)
     * 8. 스트리밍 완료 시 사용자 메시지 + 어시스턴트 응답을 DB에 저장 (비동기)
     * 9. 소스 목록과 스트림을 ChatResult로 반환
     *
     * Hybrid Search 특징:
     * - 벡터 검색: 의미적 유사도 기반 (동의어, 맥락 이해)
     * - FTS 검색: 키워드 정확도 기반 (고유명사, 코드, 버전명 등)
     * - 병렬 실행으로 지연 최소화 (순차 대비 ~2배 빠름)
     * - RRF k=60: 두 검색 결과를 균형 있게 합산하는 표준 파라미터
     */
    public ChatResult streamChat(String sessionId, String userMessage) {
        // 1. 벡터 검색 + FTS 검색 병렬 실행
        // similarityThreshold 0.5: 관련성 낮은 문서 제외 (FTS가 있어도 기준은 유지)
        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() ->
                vectorStore.similaritySearch(
                        SearchRequest.builder().query(userMessage).topK(10).similarityThreshold(0.5).build())
        );

        CompletableFuture<List<Document>> ftsFuture = CompletableFuture.supplyAsync(() ->
                ftsSearch(userMessage, 10)
        );

        List<Document> vectorDocs = vectorFuture.join();
        List<Document> ftsDocs = ftsFuture.join();

        log.info("[HybridSearch] vector={}, fts={}", vectorDocs.size(), ftsDocs.size());

        // 2. RRF로 두 결과 병합, MIN_RRF_SCORE 미만 제외 후 상위 5개 선택
        List<Document> docs = reciprocalRankFusion(vectorDocs, ftsDocs, 5);

        log.info("[HybridSearch] after RRF={}", docs.size());

        // 3. 소스 파일명 + document_id 추출 (중복 제거)
        List<Source> sources = docs.stream()
                .map(doc -> {
                    Object id = doc.getMetadata().get("document_id");
                    Object name = doc.getMetadata().get("file_name");
                    return (id != null && name != null) ? new Source(id.toString(), name.toString()) : null;
                })
                .filter(s -> s != null)
                .distinct()
                .collect(Collectors.toList());

        // 4. 검색된 청크들의 텍스트를 컨텍스트로 조합
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 5. 시스템 프롬프트에 컨텍스트 삽입 (컨텍스트가 없으면 "없음")
        String systemPrompt = String.format(SYSTEM_PROMPT, context.isBlank() ? "없음" : context);

        // 6. DB에서 기존 대화 이력 조회 및 Spring AI Message 타입으로 변환
        List<Message> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(c -> (Message) (c.getRole().equals("user")
                        ? new UserMessage(c.getContent())
                        : new AssistantMessage(c.getContent())))
                .collect(Collectors.toCollection(ArrayList::new));

        // 현재 사용자 메시지를 히스토리에 추가 (메모리상에만)
        history.add(new UserMessage(userMessage));

        // 전체 응답을 누적할 StringBuilder
        StringBuilder fullResponse = new StringBuilder();

        // 7. Spring AI ChatClient로 스트리밍 요청 (Tools 포함)
        Flux<String> stream = chatClient.prompt()
                .system(systemPrompt)    // RAG 컨텍스트가 포함된 시스템 프롬프트
                .messages(history)        // 대화 이력 전달
                .tools(weatherTool, dateTimeTool, calculatorTool)  // 도구 등록 (AI가 필요 시 자동 호출)
                .stream()                 // 스트리밍 모드
                .content()                // 텍스트 콘텐츠만 추출
                .doOnNext(fullResponse::append)  // 각 청크를 fullResponse에 누적
                .doOnComplete(() ->       // 스트리밍 완료 시
                        Schedulers.boundedElastic().schedule(() -> {  // 별도 스레드에서 비동기 실행
                            // 8. 사용자 메시지와 어시스턴트 응답을 DB에 일괄 저장
                            conversationRepository.save(new Conversation(sessionId, "user", userMessage));
                            conversationRepository.save(new Conversation(sessionId, "assistant", fullResponse.toString()));
                        })
                );

        // 9. 소스 파일 목록과 스트림을 함께 반환
        return new ChatResult(sources, stream);
    }

    /**
     * PostgreSQL Full Text Search (키워드 기반 검색)
     * GIN 인덱스를 활용한 고속 전문 검색
     *
     * @param query 검색 쿼리
     * @param topK  반환할 최대 문서 수
     * @return 검색된 Document 목록 (ts_rank 내림차순)
     *
     * 'simple' 설정:
     * - 언어 중립적 토크나이저로 한국어/영어 모두 처리 가능
     * - 어간 추출(stemming) 없이 토큰 그대로 인덱싱
     *
     * FTS 실패 시 (인덱스 미생성, 특수문자 등) 빈 목록 반환하여
     * 벡터 검색 결과만으로 폴백 처리
     */
    private List<Document> ftsSearch(String query, int topK) {
        // 쿼리를 공백으로 분리 후 OR(|)로 연결
        // plainto_tsquery는 AND로 연결해 결과가 없는 경우가 많음
        // 예) "오늘 날씨 어때" → "오늘 | 날씨 | 어때" → 하나라도 포함된 문서 검색
        String orQuery = Arrays.stream(query.trim().split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.joining(" | "));

        String sql = """
                SELECT id, content, metadata::text
                FROM vector_store
                WHERE to_tsvector('simple', content) @@ to_tsquery('simple', ?)
                ORDER BY ts_rank(to_tsvector('simple', content), to_tsquery('simple', ?)) DESC
                LIMIT ?
                """;
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        String content = rs.getString("content");
                        String metaJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metaJson);
                        return new Document(content, metadata);
                    },
                    orQuery, orQuery, topK);
        } catch (Exception e) {
            // FTS 실패 시 빈 목록 반환 — 벡터 검색 결과만으로 폴백
            log.warn("[HybridSearch] FTS failed, fallback to vector only: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Reciprocal Rank Fusion (RRF)
     * 벡터 검색 결과와 FTS 결과를 순위 기반으로 병합하여 단일 랭킹 생성
     *
     * @param vectorDocs 벡터 검색 결과 (코사인 유사도 순)
     * @param ftsDocs    FTS 검색 결과 (ts_rank 순)
     * @param topK       최종 반환할 문서 수
     * @return RRF 점수 기준 상위 topK 문서 (MIN_RRF_SCORE 미만 제외)
     *
     * RRF 공식: score(d) = Σ 1/(k + rank(d))
     * - k=60: 높은 순위 문서의 점수 차이를 완화하는 표준 파라미터
     * - 두 검색에 모두 등장한 문서는 점수가 누적되어 상위로 올라옴
     * - MIN_RRF_SCORE 필터: FTS 단독 낮은 순위 매칭 제외
     */
    private List<Document> reciprocalRankFusion(List<Document> vectorDocs, List<Document> ftsDocs, int topK) {
        final int k = 60;
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> docMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            String key = vectorDocs.get(i).getText();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docMap.putIfAbsent(key, vectorDocs.get(i));
        }

        for (int i = 0; i < ftsDocs.size(); i++) {
            String key = ftsDocs.get(i).getText();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            docMap.putIfAbsent(key, ftsDocs.get(i));
        }

        return scores.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_RRF_SCORE)  // 관련성 낮은 문서 제외
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * JSONB 메타데이터 문자열을 Map으로 파싱
     * FTS 쿼리 결과의 metadata 컬럼(JSONB → text)을 Map으로 변환
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
