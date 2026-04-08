package com.chatbot.service;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 이미지 분석 서비스
 * Spring AI의 Multimodal + Structured Output 기능을 조합하여 이미지를 분석합니다.
 * Claude Vision API를 통해 이미지를 분석하고 구조화된 결과를 반환합니다.
 */
@Service
public class ImageAnalysisService {

    /**
     * 이미지 분석 결과 레코드
     * Spring AI Multimodal + Structured Output 조합
     * 
     * @param description 이미지 전체 설명
     * @param objects     감지된 주요 객체/사물 목록
     * @param mood        이미지 분위기 또는 톤 (밝음, 어두움, 평화로움 등)
     * @param detectedText 이미지 내 텍스트 (OCR 결과, 없으면 "없음")
     * @param tags        검색용 태그 목록
     * 
     * Multimodal + Structured Output 동작 방식:
     * - Multimodal: 텍스트 프롬프트와 이미지를 함께 LLM에게 전달
     * - Structured Output: LLM 응답을 이 레코드 구조로 자동 변환
     * - Claude Vision이 이미지를 분석하고 JSON 스키마에 맞게 응답
     */
    public record ImageAnalysisResult(
            String description,
            List<String> objects,
            String mood,
            String detectedText,
            List<String> tags
    ) {}

    // 이미지 분석 프롬프트
    private static final String PROMPT = """
            이 이미지를 분석해주세요.
            한국어로 답변해주세요.
            """;

    private final ChatClient chatClient;

    /**
     * 생성자: Anthropic Claude 모델을 사용하여 ChatClient 초기화
     * @param anthropicChatModel Anthropic Claude 모델 (Vision 기능 포함)
     */
    public ImageAnalysisService(AnthropicChatModel anthropicChatModel) {
        this.chatClient = ChatClient.builder(anthropicChatModel).build();
    }

    /**
     * 이미지 분석
     * 
     * @param file 분석할 이미지 파일 (JPEG, PNG, GIF, WEBP 지원)
     * @return ImageAnalysisResult 구조화된 분석 결과
     * @throws Exception 파일 읽기 실패 시
     * 
     * 처리 흐름:
     * 1. 업로드된 파일의 MIME 타입 확인 (image/jpeg, image/png 등)
     * 2. 파일 바이트를 ByteArrayResource로 변환
     * 3. ChatClient.prompt()로 요청 생성
     * 4. .user()에 텍스트 프롬프트와 이미지를 함께 전달 (Multimodal)
     * 5. .entity(ImageAnalysisResult.class)로 Structured Output 활성화
     * 6. Spring AI가 ImageAnalysisResult 레코드 구조를 기반으로 JSON 스키마 생성
     * 7. 생성된 스키마를 프롬프트에 자동 추가하여 Claude Vision에게 전달
     * 8. Claude Vision이 이미지를 분석하고 스키마에 맞는 JSON 형식으로 응답 생성
     * 9. Spring AI가 JSON 응답을 파싱하여 ImageAnalysisResult 객체로 자동 변환
     * 10. 변환된 객체 반환
     * 
     * Multimodal 특징:
     * - 텍스트와 이미지를 동시에 LLM에게 전달 가능
     * - Claude Vision이 이미지를 이해하고 설명, 객체 감지, OCR 등 수행
     * - JPEG, PNG, GIF, WEBP 등 다양한 이미지 형식 지원
     * 
     * Structured Output 장점:
     * - 이미지 분석 결과를 일관된 구조로 받을 수 있음
     * - 타입 안정성 보장
     * - 수동 JSON 파싱 불필요
     */
    public ImageAnalysisResult analyze(MultipartFile file) throws Exception {
        // 1. 파일의 MIME 타입 확인 (기본값: image/jpeg)
        String contentType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
        MimeType mimeType = MimeType.valueOf(contentType);

        // 2. 파일 바이트를 ByteArrayResource로 변환
        ByteArrayResource imageResource = new ByteArrayResource(file.getBytes());

        // 3-10. Multimodal + Structured Output로 이미지 분석
        return chatClient.prompt()
                .user(u -> u.text(PROMPT).media(mimeType, imageResource))  // Multimodal: 텍스트 + 이미지 함께 전달
                .call()                                                     // 동기 호출
                .entity(ImageAnalysisResult.class);                         // Structured Output: JSON 응답을 자동으로 객체로 변환
    }
}
