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

        chatService.streamChat(request.getSessionId(), request.getMessage())
                .subscribe(
                        token -> {
                            try {
                                String json = objectMapper.writeValueAsString(Map.of("t", token));
                                emitter.send(json);  // 토큰마다 즉시 flush
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
