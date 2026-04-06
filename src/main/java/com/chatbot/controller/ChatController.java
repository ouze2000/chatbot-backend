package com.chatbot.controller;

import com.chatbot.dto.ChatRequest;
import com.chatbot.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        return chatService.streamChat(request.getSessionId(), request.getMessage())
                .map(token -> {
                    try {
                        // 줄바꿈 문자가 SSE 구분자로 처리되지 않도록 JSON으로 감쌈
                        return objectMapper.writeValueAsString(Map.of("t", token));
                    } catch (Exception e) {
                        return "{\"t\":\"\"}";
                    }
                });
    }
}
