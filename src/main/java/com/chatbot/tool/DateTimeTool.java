package com.chatbot.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 날짜/시간 조회 도구
 * 현재 날짜/시간 조회, 날짜 계산, 날짜 간격 계산 기능을 제공합니다.
 * Spring AI의 @Tool 어노테이션을 통해 AI가 필요 시 자동으로 호출할 수 있습니다.
 */
@Component
public class DateTimeTool {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");  // 한국 표준시
    // 날짜 포맷: 2026년 04월 08일 (화)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E)", java.util.Locale.KOREAN);
    // 날짜+시간 포맷: 2026년 04월 08일 (화) 10:18:30
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 (E) HH:mm:ss", java.util.Locale.KOREAN);

    /**
     * 현재 날짜와 시간 조회
     * 
     * @return String 현재 날짜와 시간 (한국 표준시)
     * 
     * 반환 형식 예시:
     * "2026년 04월 08일 (화) 10:18:30"
     * 
     * Spring AI Tool 통합:
     * - AI가 "지금 몇 시야?", "오늘 날짜 알려줘" 등의 질문에 자동으로 이 메서드 호출
     */
    @Tool(description = "현재 날짜와 시간을 반환합니다.")
    public String getCurrentDateTime() {
        return LocalDateTime.now(KST).format(DATETIME_FORMATTER);
    }

    /**
     * 날짜 계산 (N일 후/전)
     * 
     * @param baseDate 기준 날짜 ("today" 또는 "yyyy-MM-dd" 형식)
     * @param days 더할 일수 (양수: 이후, 음수: 이전)
     * @return String 계산된 날짜
     * 
     * 처리 흐름:
     * 1. baseDate가 "today"이면 오늘 날짜 사용, 아니면 파싱
     * 2. days만큼 날짜 더하기/빼기
     * 3. 한국어 형식으로 포맷팅하여 반환
     * 4. 에러 발생 시 안내 메시지 반환
     * 
     * 사용 예시:
     * - calculateDate("today", 7) → "2026년 04월 15일 (화)"
     * - calculateDate("2026-04-08", -3) → "2026년 04월 05일 (토)"
     * 
     * Spring AI Tool 통합:
     * - AI가 "3일 후는 언제야?", "일주일 전은?" 등의 질문에 자동으로 호출
     */
    @Tool(description = "특정 날짜로부터 며칠 후 또는 며칠 전의 날짜를 계산합니다. 양수는 이후, 음수는 이전을 의미합니다.")
    public String calculateDate(String baseDate, int days) {
        try {
            // "today" 또는 날짜 문자열 파싱
            LocalDate base = baseDate.equalsIgnoreCase("today")
                    ? LocalDate.now(KST)
                    : LocalDate.parse(baseDate);
            // 날짜 계산
            LocalDate result = base.plusDays(days);
            return result.format(DATE_FORMATTER);
        } catch (Exception e) {
            return "날짜 형식이 올바르지 않습니다. 예: 2026-04-08 또는 today";
        }
    }

    /**
     * 두 날짜 사이의 일수 차이 계산
     * 
     * @param fromDate 시작 날짜 ("yyyy-MM-dd" 형식)
     * @param toDate 종료 날짜 ("yyyy-MM-dd" 형식)
     * @return String 일수 차이
     * 
     * 처리 흐름:
     * 1. 두 날짜 문자열을 LocalDate로 파싱
     * 2. ChronoUnit.DAYS.between()으로 일수 차이 계산
     * 3. 절댓값으로 변환하여 양수로 반환
     * 4. 에러 발생 시 안내 메시지 반환
     * 
     * 반환 형식 예시:
     * "2026-04-01 부터 2026-04-08 까지 7일 입니다."
     * 
     * Spring AI Tool 통합:
     * - AI가 "크리스마스까지 며칠 남았어?", "두 날짜 사이는 몇 일이야?" 등의 질문에 자동으로 호출
     */
    @Tool(description = "두 날짜 사이의 일수 차이를 계산합니다. 날짜 형식: yyyy-MM-dd")
    public String daysBetween(String fromDate, String toDate) {
        try {
            // 날짜 문자열 파싱
            LocalDate from = LocalDate.parse(fromDate);
            LocalDate to = LocalDate.parse(toDate);
            // 일수 차이 계산
            long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
            return String.format("%s 부터 %s 까지 %d일 입니다.", fromDate, toDate, Math.abs(days));
        } catch (Exception e) {
            return "날짜 형식이 올바르지 않습니다. 예: 2026-04-08";
        }
    }
}
