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

@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant. Answer in the same language as the user's message.";

    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;

    public ChatService(AnthropicChatModel anthropicChatModel, ConversationRepository conversationRepository) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
        this.conversationRepository = conversationRepository;
    }

    public Flux<String> streamChat(String sessionId, String userMessage) {
        // 사용자 메시지 DB 저장
        conversationRepository.save(new Conversation(sessionId, "user", userMessage));

        // DB에서 대화 이력 조회 → Spring AI Message 타입으로 변환
        List<Message> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(c -> (Message) (c.getRole().equals("user")
                        ? new UserMessage(c.getContent())
                        : new AssistantMessage(c.getContent())))
                .toList();

        StringBuilder fullResponse = new StringBuilder();

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() ->
                        Schedulers.boundedElastic().schedule(() ->
                                conversationRepository.save(
                                        new Conversation(sessionId, "assistant", fullResponse.toString())
                                )
                        )
                );
    }
}
