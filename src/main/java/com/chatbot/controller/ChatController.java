package com.chatbot.controller;

import com.chatbot.dto.ChatRequest;
import com.chatbot.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stream")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        ChatService.ChatResult result = chatService.streamChat(request.getSessionId(), request.getMessage());

        try {
            // 소스 목록을 첫 번째 이벤트로 전송
            String sourcesJson = objectMapper.writeValueAsString(Map.of("sources", result.sources()));
            emitter.send(sourcesJson);
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        result.stream().subscribe(
                token -> {
                    try {
                        String json = objectMapper.writeValueAsString(Map.of("t", token));
                        emitter.send(json);
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }
}
