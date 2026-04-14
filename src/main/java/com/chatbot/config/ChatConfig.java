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
    private String deepSeekApiKey;

    /**
     * DeepSeek ChatClient 빈
     * DeepSeek는 OpenAI-compatible API를 제공하므로 Spring AI OpenAI 클라이언트를
     * base-url만 교체하여 그대로 사용할 수 있습니다.
     */
    @Bean
    public ChatClient chatClient() {
        OpenAiApi deepSeekApi = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(deepSeekApiKey)
                .build();

        OpenAiChatModel deepSeekModel = OpenAiChatModel.builder()
                .openAiApi(deepSeekApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")
                        .maxTokens(4096)
                        .build())
                .build();

        return ChatClient.builder(deepSeekModel).build();
    }
}
