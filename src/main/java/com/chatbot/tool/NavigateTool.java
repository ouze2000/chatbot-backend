package com.chatbot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 프론트엔드 페이지 이동을 요청하는 AI 도구
 * AI가 이 도구를 호출하면 NavigationHolder에 경로를 저장하고,
 * 스트림 완료 후 ChatController가 SSE 이벤트 {"navigate": "/path"}로 프론트에 전달합니다.
 *
 * 사용 가능한 경로:
 * - /          : 채팅 페이지 (홈)
 * - /documents : 문서 관리 페이지
 * - /analysis  : 텍스트 분석 페이지
 */
@Component
public class NavigateTool {

    private final NavigationHolder navigationHolder;

    public NavigateTool(NavigationHolder navigationHolder) {
        this.navigationHolder = navigationHolder;
    }

    /**
     * 사용자를 지정한 페이지로 이동시킵니다.
     * @param sessionId 현재 채팅 세션 ID (시스템 프롬프트에서 확인하세요)
     * @param path 이동할 경로. 사용 가능한 값: "/" (채팅), "/documents" (문서 관리), "/analysis" (텍스트 분석)
     * @return 처리 결과 메시지
     */
    @Tool(description = """
            사용자를 특정 페이지로 이동시킵니다.
            sessionId는 시스템 프롬프트에 명시된 현재 세션 ID를 그대로 사용하세요.
            사용 가능한 경로: "/" (채팅 홈), "/documents" (문서 관리), "/analysis" (텍스트 분석)
            """)
    public String navigate(String sessionId, String path) {
        navigationHolder.set(sessionId, path);
        return "페이지 이동 요청이 등록되었습니다: " + path;
    }
}
