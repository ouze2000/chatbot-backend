package com.chatbot.controller;

import com.chatbot.dto.ChatRequest;
import com.chatbot.service.ChatService;
import com.chatbot.tool.NavigationHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 채팅 컨트롤러
 * SSE(Server-Sent Events)를 사용하여 실시간 스트리밍 채팅 API를 제공합니다.
 * RAG 기반으로 관련 문서를 검색하여 컨텍스트와 함께 응답합니다.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final NavigationHolder navigationHolder;

    /**
     * 생성자: ChatService, ObjectMapper, NavigationHolder 주입
     * @param chatService 채팅 서비스
     * @param objectMapper JSON 직렬화/역직렬화용
     * @param navigationHolder AI가 요청한 페이지 이동 경로 보관소
     */
    public ChatController(ChatService chatService, ObjectMapper objectMapper, NavigationHolder navigationHolder) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.navigationHolder = navigationHolder;
    }

    /**
     * SSE 스트리밍 채팅 엔드포인트
     * 
     * @param request 채팅 요청 (sessionId, message)
     * @return SseEmitter SSE 스트리밍 응답
     * 
     * 응답 형식:
     * 1. 첫 번째 이벤트: {"sources": [{"id": "1", "fileName": "example.pdf"}, ...]}
     *    - RAG 검색으로 참조한 소스 문서 목록
     * 2. 이후 이벤트들: {"t": "토큰"}
     *    - AI 응답을 토큰 단위로 실시간 전송
     * 
     * 처리 흐름:
     * 1. SseEmitter 생성 (타임아웃: 5분)
     * 2. ChatService로 RAG 기반 스트리밍 요청
     * 3. 소스 목록을 첫 번째 이벤트로 전송
     * 4. Flux 스트림을 구독하여 각 토큰을 SSE 이벤트로 전송
     * 5. 완료 또는 에러 시 emitter 종료
     */
    @PostMapping("/stream")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // SSE Emitter 생성 (타임아웃: 5분)
        SseEmitter emitter = new SseEmitter(300_000L);

        // ChatService로 RAG 기반 스트리밍 요청
        ChatService.ChatResult result = chatService.streamChat(request.getSessionId(), request.getMessage());

        try {
            // 소스 목록을 첫 번째 이벤트로 전송 (클라이언트가 출처를 먼저 받음)
            String sourcesJson = objectMapper.writeValueAsString(Map.of("sources", result.sources()));
            emitter.send(sourcesJson);
        } catch (Exception e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // Flux 스트림을 구독하여 각 토큰을 SSE 이벤트로 전송
        result.stream().subscribe(
                token -> {  // onNext: 각 토큰 수신 시
                    try {
                        // 토큰을 JSON으로 감싸서 전송 {"t": "토큰"}
                        String json = objectMapper.writeValueAsString(Map.of("t", token));
                        emitter.send(json);
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,  // onError: 에러 발생 시
                () -> {                       // onComplete: 스트림 완료 시
                    try {
                        // NavigateTool이 경로를 등록했으면 navigate 이벤트 전송
                        String navPath = navigationHolder.getAndClear(request.getSessionId());
                        if (navPath != null) {
                            emitter.send(objectMapper.writeValueAsString(Map.of("navigate", navPath)));
                        }
                    } catch (Exception ignored) {
                    }
                    emitter.complete();
                }
        );

        return emitter;
    }
}
