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
     * 
     * 처리 흐름:
     * 1. 사용자 메시지를 DB에 저장 (동기 블로킹)
     * 2. 세션의 전체 대화 이력을 DB에서 조회
     * 3. Spring AI Message 타입으로 변환 (UserMessage/AssistantMessage)
     * 4. ChatClient로 스트리밍 요청
     * 5. 각 응답 청크를 클라이언트로 전송하면서 fullResponse에 누적
     * 6. 스트리밍 완료 시 어시스턴트 응답을 DB에 저장 (비동기)
     * 
     * 주의: 
     * - 사용자 메시지 저장은 블로킹 작업 (개선 필요)
     * - 어시스턴트 응답 저장은 boundedElastic 스케줄러로 비동기 처리
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        // 사용자 메시지 DB 저장 (블로킹)
        conversationRepository.save(new Conversation(sessionId, "user", userMessage));

        // DB에서 대화 이력 조회 → Spring AI Message 타입으로 변환
        List<Message> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(c -> (Message) (c.getRole().equals("user")
                        ? new UserMessage(c.getContent())
                        : new AssistantMessage(c.getContent())))
                .toList();

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
                        Schedulers.boundedElastic().schedule(() ->  // 별도 스레드에서 비동기 실행
                                conversationRepository.save(
                                        new Conversation(sessionId, "assistant", fullResponse.toString())
                                )
                        )
                );
    }
}
