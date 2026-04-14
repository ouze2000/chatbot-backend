package com.chatbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * NewsData.io 뉴스 검색 도구
 * 키워드 또는 카테고리로 최신 뉴스 기사를 검색합니다.
 * Spring AI의 @Tool 어노테이션을 통해 AI가 필요 시 자동으로 호출합니다.
 */
@Component
public class NewsTool {

    private static final Logger log = LoggerFactory.getLogger(NewsTool.class);
    private static final String BASE_URL = "https://newsdata.io/api/1/latest";
    // 반환할 최대 기사 수
    private static final int MAX_RESULTS = 5;

    @Value("${app.newsdata.api-key}")
    private String apiKey;

    @jakarta.annotation.PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("NewsTool: app.newsdata.api-key 가 설정되지 않았습니다.");
        } else {
            log.info("NewsTool: API key 로드됨 (앞 4자리: {}****)", apiKey.substring(0, Math.min(4, apiKey.length())));
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 키워드로 최신 뉴스 검색
     *
     * @param query    검색 키워드 (예: "AI", "경제", "날씨")
     * @param language 언어 코드 (예: "ko" = 한국어, "en" = 영어, 생략 시 전체)
     * @return 뉴스 기사 목록 (제목, 설명, 출처, 날짜 포함)
     *
     * 처리 흐름:
     * 1. NewsData.io /latest API 호출
     * 2. 응답 JSON에서 results 배열 파싱
     * 3. 기사별 제목/설명/출처/날짜 추출
     * 4. 포맷된 문자열로 반환
     */
    @Tool(description = """
            최신 뉴스를 검색합니다. 특정 키워드나 주제에 대한 최신 뉴스 기사를 가져올 때 사용합니다.
            query: 검색할 키워드 (필수, 예: "AI", "경제", "스포츠"). 반드시 관련 키워드를 지정하세요.
            language: 언어 코드 (선택, 예: "ko"=한국어, "en"=영어). 생략 가능.
            """)
    public String searchNews(String query, String language) {
        if (apiKey == null || apiKey.isBlank()) {
            return "NewsData.io API 키가 설정되지 않았습니다. NEWSDATA_API_KEY 환경변수를 확인하세요.";
        }
        if (query == null || query.isBlank()) {
            return "검색 키워드를 입력해주세요.";
        }

        try {
            // 1. 요청 URL 빌드
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                    .queryParam("apikey", apiKey)
                    .queryParam("q", query)
                    .queryParam("size", MAX_RESULTS);

            if (language != null && !language.isBlank()) {
                uriBuilder.queryParam("language", language);
            }

            URI uri = uriBuilder.build().toUri();

            // 2. HTTP GET 요청
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("NewsData.io API 오류: status={}, body={}", response.statusCode(), response.body());
                return "뉴스 API 오류 (HTTP " + response.statusCode() + "): " + response.body();
            }

            // 3. JSON 파싱
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");

            if (results.isEmpty()) {
                return "'" + query + "'에 대한 뉴스를 찾을 수 없습니다.";
            }

            // 4. 기사 목록 포맷
            List<String> articles = new ArrayList<>();
            for (JsonNode article : results) {
                String title       = article.path("title").asText("(제목 없음)");
                String description = article.path("description").asText("");
                String source      = article.path("source_id").asText("unknown");
                String pubDate     = article.path("pubDate").asText("");

                StringBuilder sb = new StringBuilder();
                sb.append("■ ").append(title).append("\n");
                if (!description.isBlank()) {
                    // 설명이 너무 길면 자름
                    String desc = description.length() > 200 ? description.substring(0, 200) + "..." : description;
                    sb.append("  ").append(desc).append("\n");
                }
                sb.append("  출처: ").append(source);
                if (!pubDate.isBlank()) sb.append(" | ").append(pubDate);

                articles.add(sb.toString());
            }

            String result = "['" + query + "' 최신 뉴스]\n\n" + String.join("\n\n", articles);
            log.info("뉴스 검색 완료: query={}, count={}", query, articles.size());
            return result;

        } catch (Exception e) {
            log.error("뉴스 검색 실패: query={} - {}", query, e.getMessage());
            return "뉴스를 가져오는 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
