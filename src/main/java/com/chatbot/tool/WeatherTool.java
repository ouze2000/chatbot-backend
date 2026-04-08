package com.chatbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 날씨 조회 도구
 * OpenWeatherMap API를 사용하여 특정 도시의 현재 날씨 정보를 조회합니다.
 * Spring AI의 @Tool 어노테이션을 통해 AI가 필요 시 자동으로 호출할 수 있습니다.
 */
@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    // OpenWeatherMap API URL (섭씨 온도, 한국어 응답)
    private static final String API_URL = "https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s&units=metric&lang=ko";

    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 생성자: OpenWeatherMap API 키 주입
     * @param apiKey OpenWeatherMap API 키 (application.yml의 app.weather-api-key)
     */
    public WeatherTool(@Value("${app.weather-api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 특정 도시의 현재 날씨 조회
     * 
     * @param city 도시명 (영어, 예: Seoul, Busan, Jeju)
     * @return String 날씨 정보 문자열
     * 
     * 처리 흐름:
     * 1. OpenWeatherMap API에 HTTP GET 요청
     * 2. JSON 응답 파싱
     * 3. 날씨 정보 추출 (날씨 설명, 기온, 체감온도, 습도, 풍속)
     * 4. 포맷팅된 문자열 반환
     * 5. 에러 발생 시 에러 메시지 반환
     * 
     * Spring AI Tool 통합:
     * - @Tool 어노테이션으로 AI가 자동으로 이 메서드를 호출 가능
     * - AI가 사용자 질문에서 날씨 정보가 필요하다고 판단하면 자동 실행
     * - description은 AI가 도구 사용 여부를 결정하는 데 사용됨
     * 
     * 반환 형식 예시:
     * "서울 날씨: 맑음, 기온 15.3°C (체감 14.2°C), 습도 65%, 풍속 2.5m/s"
     */
    @Tool(description = "특정 도시의 현재 날씨를 조회합니다. 도시명은 영어로 입력합니다. 예: Seoul, Busan, Jeju")
    public String getWeather(String city) {
        try {
            // 1. OpenWeatherMap API URL 생성
            String url = String.format(API_URL, city, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            // 2. HTTP 요청 전송 및 응답 수신
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            // 에러 응답 처리
            if (response.statusCode() != 200) {
                return "날씨 정보를 가져올 수 없습니다: " + root.path("message").asText();
            }

            // 3. JSON에서 날씨 정보 추출
            String description = root.path("weather").get(0).path("description").asText();  // 날씨 설명 (예: 맑음)
            double temp = root.path("main").path("temp").asDouble();                        // 현재 기온
            double feelsLike = root.path("main").path("feels_like").asDouble();             // 체감 온도
            int humidity = root.path("main").path("humidity").asInt();                      // 습도 (%)
            double windSpeed = root.path("wind").path("speed").asDouble();                  // 풍속 (m/s)
            String cityName = root.path("name").asText();                                    // 도시명 (한국어)

            // 4. 포맷팅된 날씨 정보 문자열 생성
            return String.format("%s 날씨: %s, 기온 %.1f°C (체감 %.1f°C), 습도 %d%%, 풍속 %.1fm/s",
                    cityName, description, temp, feelsLike, humidity, windSpeed);

        } catch (Exception e) {
            // 5. 에러 발생 시 로그 기록 및 에러 메시지 반환
            log.error("날씨 조회 실패: {}", e.getMessage());
            return "날씨 정보를 가져오는 중 오류가 발생했습니다.";
        }
    }
}
