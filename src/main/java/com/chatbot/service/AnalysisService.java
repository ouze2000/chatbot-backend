package com.chatbot.service;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 텍스트 분석 서비스
 * Spring AI의 Structured Output 기능을 사용하여 LLM 응답을 구조화된 자바 객체로 자동 변환합니다.
 * Claude API를 통해 텍스트를 분석하고 요약, 감정, 핵심 포인트 등을 추출합니다.
 */
@Service
public class AnalysisService {

    /**
     * 텍스트 분석 결과 레코드
     * Spring AI Structured Output으로 LLM 응답을 자바 객체로 자동 변환
     * 
     * @param summary    한 문장 요약
     * @param sentiment  감정 분석 결과 (긍정 / 부정 / 중립)
     * @param keyPoints  핵심 포인트 목록
     * @param language   감지된 언어 (한국어, 영어 등)
     * @param category   주제 카테고리 (기술, 비즈니스, 일상 등)
     * 
     * Structured Output 동작 방식:
     * - Spring AI가 이 레코드 구조를 기반으로 JSON 스키마를 자동 생성
     * - 생성된 스키마를 프롬프트에 포함하여 LLM에게 전달
     * - LLM이 스키마에 맞는 JSON 응답 생성
     * - Spring AI가 JSON을 파싱하여 이 레코드 객체로 자동 변환
     */
    public record AnalysisResult(
            String summary,
            String sentiment,
            List<String> keyPoints,
            String language,
            String category
    ) {}

    // 텍스트 분석 프롬프트 템플릿
    private static final String PROMPT = """
            아래 텍스트를 분석해주세요.

            텍스트:
            %s
            """;

    private final ChatClient chatClient;

    /**
     * 생성자: Anthropic Claude 모델을 사용하여 ChatClient 초기화
     * @param anthropicChatModel Anthropic Claude 모델
     */
    public AnalysisService(AnthropicChatModel anthropicChatModel) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
    }

    /**
     * 텍스트 분석
     * 
     * @param text 분석할 텍스트
     * @return AnalysisResult 구조화된 분석 결과
     * 
     * 처리 흐름:
     * 1. 프롬프트 템플릿에 분석할 텍스트 삽입
     * 2. ChatClient.prompt()로 요청 생성
     * 3. .entity(AnalysisResult.class)로 Structured Output 활성화
     * 4. Spring AI가 AnalysisResult 레코드 구조를 기반으로 JSON 스키마 생성
     * 5. 생성된 스키마를 프롬프트에 자동 추가하여 Claude에게 전달
     * 6. Claude가 스키마에 맞는 JSON 형식으로 응답 생성
     * 7. Spring AI가 JSON 응답을 파싱하여 AnalysisResult 객체로 자동 변환
     * 8. 변환된 객체 반환
     * 
     * Structured Output 장점:
     * - 수동 JSON 파싱 불필요
     * - 타입 안정성 보장
     * - LLM 응답 형식 일관성 유지
     * - 코드 간결성 향상
     */
    public AnalysisResult analyze(String text) {
        return chatClient.prompt()
                .user(String.format(PROMPT, text))  // 분석할 텍스트를 프롬프트에 삽입
                .call()                              // 동기 호출
                .entity(AnalysisResult.class);       // Structured Output: JSON 응답을 자동으로 객체로 변환
    }
}
