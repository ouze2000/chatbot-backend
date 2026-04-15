package com.chatbot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 채팅용 ChatClient 빈 설정
 *
 * 모델 전환 방법:
 * - DeepSeek  → 현재 설정 유지 (app.deepseek.api-key 환경변수 필요)
 * - Claude    → ChatClient.builder(anthropicChatModel).build() 로 교체
 * - OpenAI    → baseUrl 제거 후 OpenAI API 키 사용
 *
 * AnalysisService / ImageAnalysisService는 Claude Vision/Structured Output이 필요하므로
 * AnthropicChatModel을 직접 사용하며 이 설정과 무관합니다.
 */
@Configuration
public class ChatConfig {

    @Value("${app.deepseek.api-key}")
    private String apiKey;

    @Value("${app.deepseek.base-url}")
    private String baseUrl;

    @Value("${app.deepseek.model}")
    private String model;

    @Value("${app.deepseek.max-tokens}")
    private int maxTokens;

    /**
     * ChatClient 빈 (OpenAI-compatible API)
     * base-url을 변경하면 DeepSeek, Ollama 등 다른 OpenAI-compatible 엔드포인트로 전환 가능.
     * app.deepseek.base-url / app.deepseek.api-key 설정 필요.
     */
    @Bean
    public ChatClient chatClient() {
        // OpenAI-compatible API 클라이언트 (base-url 교체로 다른 엔드포인트 사용 가능)
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        // 모델명 및 최대 토큰 수는 application.yml에서 관리
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .build())
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
