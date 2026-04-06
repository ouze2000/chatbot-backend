package com.chatbot.service;

import com.chatbot.entity.Conversation;
import com.chatbot.repository.ConversationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String SYSTEM_PROMPT = "You are a helpful assistant. Answer in the same language as the user's message.";

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;

    public ChatService(ObjectMapper objectMapper, ConversationRepository conversationRepository) {
        this.objectMapper = objectMapper;
        this.conversationRepository = conversationRepository;
    }

    public Flux<String> streamChat(String sessionId, String userMessage) {
        // 사용자 메시지 DB 저장
        conversationRepository.save(new Conversation(sessionId, "user", userMessage));

        // DB에서 대화 이력 조회
        List<Map<String, String>> history = conversationRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(c -> Map.of("role", c.getRole(), "content", c.getContent()))
                .toList();

        String requestBody = buildRequestBody(history);
        log.info("Sending request to Anthropic: model={}, messages={}", model, history.size());

        return Flux.<String>create(sink -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            StringBuilder fullResponse = new StringBuilder();

            try {
                HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofLines());

                if (response.statusCode() != 200) {
                    String errorBody = response.body().reduce("", (a, b) -> a + b);
                    log.error("Anthropic error {}: {}", response.statusCode(), errorBody);
                    sink.error(new RuntimeException("Anthropic API error: " + errorBody));
                    return;
                }

                response.body().forEach(line -> {
                    if (sink.isCancelled()) return;
                    if (!line.startsWith("data:")) return;

                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) return;

                    try {
                        JsonNode node = objectMapper.readTree(data);
                        if ("content_block_delta".equals(node.path("type").asText())) {
                            String text = node.path("delta").path("text").asText("");
                            if (!text.isEmpty()) {
                                fullResponse.append(text);
                                sink.next(text);
                            }
                        }
                    } catch (Exception ignored) {}
                });

                sink.complete();

                // 스트리밍 완료 후 DB 저장 (별도 스레드, 클라이언트 대기 없음)
                String assistantContent = fullResponse.toString();
                Schedulers.boundedElastic().schedule(() ->
                    conversationRepository.save(new Conversation(sessionId, "assistant", assistantContent))
                );

            } catch (Exception e) {
                log.error("HTTP request failed", e);
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildRequestBody(List<Map<String, String>> history) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 4096);
            body.put("stream", true);
            body.put("system", SYSTEM_PROMPT);

            ArrayNode messages = body.putArray("messages");
            for (Map<String, String> msg : history) {
                ObjectNode m = messages.addObject();
                m.put("role", msg.get("role"));
                m.put("content", msg.get("content"));
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }
}
