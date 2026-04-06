package com.chatbot.service;

import com.chatbot.entity.Conversation;
import com.chatbot.repository.ConversationRepository;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 채팅 서비스
 * Spring AI를 사용하여 Anthropic Claude API와 통신하고,
 * 대화 내역을 PostgreSQL DB에 저장합니다.
 */
@Service
public class ChatService {

    // AI 어시스턴트의 기본 시스템 프롬프트
    private static final String SYSTEM_PROMPT = "You are a helpful assistant. Answer in the same language as the user's message.";

    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;

    /**
     * 생성자: Spring AI ChatClient 초기화 및 Repository 주입
     * @param anthropicChatModel Anthropic Claude 모델
     * @param conversationRepository 대화 내역 저장소
     */
    public ChatService(AnthropicChatModel anthropicChatModel, ConversationRepository conversationRepository) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
        this.conversationRepository = conversationRepository;
    }

    /**
     * SSE 스트리밍 채팅 처리
     * 
     * @param sessionId 세션 ID (대화 구분용)
     * @param userMessage 사용자 메시지
     * @return Flux<String> 스트리밍 응답 (각 토큰이 실시간으로 전송됨)
     * 처리 흐름:
     * 1. DB에서 세션의 기존 대화 이력 조회 (블로킹)
     * 2. Spring AI Message 타입으로 변환 (UserMessage/AssistantMessage)
     * 3. 현재 사용자 메시지를 히스토리에 추가 (메모리상에만, DB 저장 안 함)
     * 4. ChatClient로 스트리밍 요청
     * 5. 각 응답 청크를 클라이언트로 실시간 전송하면서 fullResponse에 누적
     * 6. 스트리밍 완료 시 사용자 메시지 + 어시스턴트 응답을 DB에 저장 (비동기)
     * 장점:
     * - 스트리밍 시작 전 블로킹 DB 저장 없음 → 응답 지연 최소화
     * - 스트리밍 완료 후 별도 스레드에서 일괄 저장 → 메인 Flux 블로킹 없음
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        // DB에서 기존 대화 이력 조회 (블로킹이지만 스트리밍 시작 전 필수)
        List<Message> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(c -> (Message) (c.getRole().equals("user")
                        ? new UserMessage(c.getContent())
                        : new AssistantMessage(c.getContent())))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        // 현재 사용자 메시지를 히스토리에 추가 (메모리상에만)
        history.add(new UserMessage(userMessage));

        // 전체 응답을 누적할 StringBuilder
        StringBuilder fullResponse = new StringBuilder();

        // Spring AI ChatClient로 스트리밍 요청
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)  // 시스템 프롬프트 설정
                .messages(history)       // 대화 이력 전달
                .stream()                // 스트리밍 모드
                .content()               // 텍스트 콘텐츠만 추출
                .doOnNext(fullResponse::append)  // 각 청크를 fullResponse에 누적
                .doOnComplete(() ->      // 스트리밍 완료 시
                        Schedulers.boundedElastic().schedule(() -> {  // 별도 스레드에서 비동기 실행
                            // 사용자 메시지와 어시스턴트 응답을 DB에 일괄 저장
                            conversationRepository.save(new Conversation(sessionId, "user", userMessage));
                            conversationRepository.save(new Conversation(sessionId, "assistant", fullResponse.toString()));
                        })
                );
    }
}
