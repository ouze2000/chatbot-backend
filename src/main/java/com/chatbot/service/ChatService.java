package com.chatbot.service;

import com.chatbot.entity.Conversation;
import com.chatbot.repository.ConversationRepository;
import com.chatbot.tool.CalculatorTool;
import com.chatbot.tool.DateTimeTool;
import com.chatbot.tool.NavigateTool;
import com.chatbot.tool.NewsTool;
import com.chatbot.tool.WeatherTool;
import com.chatbot.tool.WebCrawlTool;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 기반 채팅 서비스
 * Spring AI를 사용하여 Anthropic Claude API와 통신하고,
 * 벡터 스토어에서 관련 문서를 검색하여 컨텍스트로 제공합니다.
 * WeatherTool, DateTimeTool, CalculatorTool을 통해 AI가 필요 시 외부 정보를 조회하거나 계산을 수행할 수 있습니다.
 * 대화 내역은 PostgreSQL DB에 저장됩니다.
 */
@Service
public class ChatService {

    // RAG 시스템 프롬프트: 컨텍스트를 우선 사용하도록 지시
    // %s 순서: 1) sessionId, 2) context
    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant. Answer in the same language as the user's message.

            Current session ID: %s
            (Use this session ID when calling the navigate tool.)

            If the following context is provided, use it as the primary source to answer the question.
            If the context is not relevant to the question, answer based on your general knowledge.

            Context:
            %s
            """;

    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;
    private final VectorStore vectorStore;
    private final WeatherTool weatherTool;
    private final DateTimeTool dateTimeTool;
    private final CalculatorTool calculatorTool;
    private final WebCrawlTool webCrawlTool;
    private final NewsTool newsTool;
    private final NavigateTool navigateTool;

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
     * 생성자: ChatClient, Repository, VectorStore, Tools 주입
     * @param anthropicChatModel Anthropic Claude 모델
     * @param conversationRepository 대화 내역 저장소
     * @param vectorStore 벡터 임베딩 저장소 (RAG용)
     * @param weatherTool 날씨 조회 도구 (AI가 필요 시 자동 호출)
     * @param dateTimeTool 날짜/시간 조회 도구 (AI가 필요 시 자동 호출)
     * @param calculatorTool 계산기 도구 (AI가 필요 시 자동 호출)
     */
    public ChatService(AnthropicChatModel anthropicChatModel,
                       ConversationRepository conversationRepository,
                       VectorStore vectorStore,
                       WeatherTool weatherTool,
                       DateTimeTool dateTimeTool,
                       CalculatorTool calculatorTool,
                       WebCrawlTool webCrawlTool,
                       NewsTool newsTool,
                       NavigateTool navigateTool) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
        this.conversationRepository = conversationRepository;
        this.vectorStore = vectorStore;
        this.weatherTool = weatherTool;
        this.dateTimeTool = dateTimeTool;
        this.calculatorTool = calculatorTool;
        this.webCrawlTool = webCrawlTool;
        this.newsTool = newsTool;
        this.navigateTool = navigateTool;
    }

    /**
     * RAG + Tool 기반 SSE 스트리밍 채팅 처리
     * 
     * @param sessionId 세션 ID (대화 구분용)
     * @param userMessage 사용자 메시지
     * @return ChatResult 소스 파일 목록과 스트리밍 응답
     * 
     * 처리 흐름:
     * 1. 벡터 스토어에서 사용자 질문과 유사한 문서 청크 검색 (상위 5개, 유사도 임계값 0.5)
     * 2. 검색된 청크의 소스 파일명 추출 (중복 제거)
     * 3. 청크 텍스트를 컨텍스트로 조합
     * 4. 시스템 프롬프트에 컨텍스트 삽입
     * 5. DB에서 대화 이력 조회 및 현재 메시지 추가
     * 6. ChatClient로 스트리밍 요청 (WeatherTool 포함)
     * 7. 스트리밍 완료 시 사용자 메시지 + 어시스턴트 응답을 DB에 저장 (비동기)
     * 8. 소스 목록과 스트림을 ChatResult로 반환
     * 
     * RAG 특징:
     * - 벡터 유사도 검색으로 관련 문서 자동 탐색
     * - similarityThreshold 0.5로 적당한 관련성의 문서만 포함
     * - 검색된 컨텍스트를 AI에게 제공하여 정확도 향상
     * - 소스 파일명을 반환하여 출처 추적 가능
     * 
     * Tool 통합:
     * - WeatherTool을 ChatClient에 등록하여 AI가 필요 시 자동으로 날씨 정보 조회
     * - AI가 사용자 질문을 분석하여 도구 사용 여부를 자동 판단
     */
    public ChatResult streamChat(String sessionId, String userMessage) {
        // 1. 벡터 스토어에서 유사 청크 검색 (코사인 유사도 기반, 상위 5개)
        // 수치 조정 기준:
        // - 0.5 (현재) — 기본값, 적당한 관련성
        // - 0.7 — 엄격, 매우 관련성 높은 문서만
        // - 0.3 — 느슨, 조금이라도 관련 있으면 포함
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(userMessage).topK(5).similarityThreshold(0.5).build());

        // 2. 소스 파일명 + document_id 추출 (중복 제거)
        List<Source> sources = docs.stream()
                .map(doc -> {
                    Object id = doc.getMetadata().get("document_id");
                    Object name = doc.getMetadata().get("file_name");
                    return (id != null && name != null) ? new Source(id.toString(), name.toString()) : null;
                })
                .filter(s -> s != null)
                .distinct()
                .collect(Collectors.toList());

        // 3. 검색된 청크들의 텍스트를 컨텍스트로 조합
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 4. 시스템 프롬프트에 세션 ID + 컨텍스트 삽입 (컨텍스트가 없으면 "없음")
        String systemPrompt = String.format(SYSTEM_PROMPT, sessionId, context.isBlank() ? "없음" : context);

        // 5. DB에서 기존 대화 이력 조회 및 Spring AI Message 타입으로 변환
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

        // 6. Spring AI ChatClient로 스트리밍 요청 (WeatherTool 포함)
        Flux<String> stream = chatClient.prompt()
                .system(systemPrompt)    // RAG 컨텍스트가 포함된 시스템 프롬프트
                .messages(history)        // 대화 이력 전달
                .tools(weatherTool, dateTimeTool, calculatorTool, webCrawlTool, newsTool, navigateTool)  // 도구 등록 (AI가 필요 시 자동 호출)
                .stream()                 // 스트리밍 모드
                .content()                // 텍스트 콘텐츠만 추출
                .doOnNext(fullResponse::append)  // 각 청크를 fullResponse에 누적
                .doOnComplete(() ->       // 스트리밍 완료 시
                        Schedulers.boundedElastic().schedule(() -> {  // 별도 스레드에서 비동기 실행
                            // 7. 사용자 메시지와 어시스턴트 응답을 DB에 일괄 저장
                            conversationRepository.save(new Conversation(sessionId, "user", userMessage));
                            conversationRepository.save(new Conversation(sessionId, "assistant", fullResponse.toString()));
                        })
                );

        // 8. 소스 파일 목록과 스트림을 함께 반환
        return new ChatResult(sources, stream);
    }
}
