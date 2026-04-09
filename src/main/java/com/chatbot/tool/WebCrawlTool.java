package com.chatbot.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 웹 크롤링 도구
 * 주어진 URL의 웹 페이지를 가져와 본문 텍스트를 추출합니다.
 * Spring AI의 @Tool 어노테이션을 통해 AI가 필요 시 자동으로 호출할 수 있습니다.
 */
@Component
public class WebCrawlTool {

    private static final Logger log = LoggerFactory.getLogger(WebCrawlTool.class);
    // 응답 텍스트 최대 길이 (너무 길면 컨텍스트 초과)
    private static final int MAX_LENGTH = 3000;
    private static final int TIMEOUT_MS = 10_000;

    /**
     * URL의 웹 페이지 본문 텍스트 추출
     *
     * @param url 크롤링할 웹 페이지 URL
     * @return String 페이지 제목 + 본문 텍스트 (최대 3000자)
     *
     * 처리 흐름:
     * 1. Jsoup으로 URL에 HTTP GET 요청
     * 2. <script>, <style> 태그 제거
     * 3. 페이지 제목과 본문 텍스트 추출
     * 4. 텍스트가 MAX_LENGTH 초과 시 잘라내기
     * 5. 에러 발생 시 에러 메시지 반환
     *
     * Spring AI Tool 통합:
     * - AI가 "이 URL 내용 요약해줘", "해당 페이지에 뭐라고 써있어?" 등의 질문에 자동 호출
     */
    @Tool(description = "주어진 URL의 웹 페이지 내용을 가져옵니다. 페이지 요약, 특정 정보 추출 등에 사용합니다.")
    public String crawl(String url) {
        try {
            // 1. Jsoup으로 페이지 로드
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; ChatbotCrawler/1.0)")
                    .timeout(TIMEOUT_MS)
                    .get();

            // 2. 불필요한 태그 제거
            doc.select("script, style, nav, footer, header, iframe, noscript").remove();

            // 3. 제목 + 본문 텍스트 추출
            String title = doc.title();
            String body = doc.body().text();

            String content = "[제목] " + title + "\n\n[본문]\n" + body;

            // 4. 최대 길이 제한
            if (content.length() > MAX_LENGTH) {
                content = content.substring(0, MAX_LENGTH) + "...(이하 생략)";
            }

            log.info("웹 크롤링 완료: {} ({} 자)", url, content.length());
            return content;

        } catch (Exception e) {
            log.error("웹 크롤링 실패: {} - {}", url, e.getMessage());
            return "웹 페이지를 가져오는 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
