package com.chatbot.tool;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * AI가 요청한 페이지 이동 경로를 세션 ID 기준으로 임시 보관하는 싱글턴 빈
 * NavigateTool → NavigationHolder.set → ChatController.getAndClear → SSE 이벤트 전송 흐름으로 사용됩니다.
 */
@Component
public class NavigationHolder {

    // 세션 ID → 이동할 경로 (스트림 완료 후 컨트롤러가 꺼내 가면 자동 삭제)
    private final ConcurrentHashMap<String, String> pendingNavigations = new ConcurrentHashMap<>();

    /**
     * 세션에 대한 네비게이션 경로 등록
     * @param sessionId 채팅 세션 ID
     * @param path 이동할 프론트엔드 경로 (예: "/documents")
     */
    public void set(String sessionId, String path) {
        pendingNavigations.put(sessionId, path);
    }

    /**
     * 세션의 네비게이션 경로를 꺼내고 삭제 (1회성 소비)
     * @param sessionId 채팅 세션 ID
     * @return 등록된 경로, 없으면 null
     */
    public String getAndClear(String sessionId) {
        return pendingNavigations.remove(sessionId);
    }
}
