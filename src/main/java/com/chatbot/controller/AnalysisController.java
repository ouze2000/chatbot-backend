package com.chatbot.controller;

import com.chatbot.dto.AnalysisRequest;
import com.chatbot.service.AnalysisService;
import com.chatbot.service.ImageAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 분석 컨트롤러
 * 텍스트 분석 및 이미지 분석 API를 제공합니다.
 * Spring AI의 Structured Output과 Multimodal 기능을 활용합니다.
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final ImageAnalysisService imageAnalysisService;

    /**
     * 생성자: AnalysisService, ImageAnalysisService 주입
     * @param analysisService 텍스트 분석 서비스
     * @param imageAnalysisService 이미지 분석 서비스
     */
    public AnalysisController(AnalysisService analysisService, ImageAnalysisService imageAnalysisService) {
        this.analysisService = analysisService;
        this.imageAnalysisService = imageAnalysisService;
    }

    /**
     * 텍스트 분석 엔드포인트
     * 
     * @param request 분석 요청 (text 필드 포함)
     * @return ResponseEntity<AnalysisResult> 구조화된 분석 결과
     * 
     * 처리 흐름:
     * 1. 요청 본문에서 분석할 텍스트 추출
     * 2. AnalysisService.analyze() 호출
     * 3. Claude API가 텍스트를 분석하여 구조화된 결과 생성
     * 4. AnalysisResult 객체 반환 (요약, 감정, 핵심 포인트, 언어, 카테고리)
     * 
     * 반환 데이터:
     * - summary: 한 문장 요약
     * - sentiment: 감정 분석 (긍정/부정/중립)
     * - keyPoints: 핵심 포인트 목록
     * - language: 감지된 언어
     * - category: 주제 카테고리
     * 
     * 사용 예시:
     * POST /api/analysis
     * Body: {"text": "분석할 텍스트 내용"}
     */
    @PostMapping
    public ResponseEntity<AnalysisService.AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        return ResponseEntity.ok(analysisService.analyze(request.getText()));
    }

    /**
     * 이미지 분석 엔드포인트
     * 
     * @param file 분석할 이미지 파일 (JPEG, PNG, GIF, WEBP)
     * @return ResponseEntity<ImageAnalysisResult> 구조화된 이미지 분석 결과
     * @throws Exception 파일 처리 실패 시
     * 
     * 처리 흐름:
     * 1. multipart/form-data로 업로드된 이미지 파일 수신
     * 2. ImageAnalysisService.analyze() 호출
     * 3. Claude Vision API가 이미지를 분석하여 구조화된 결과 생성
     * 4. ImageAnalysisResult 객체 반환
     * 
     * 반환 데이터:
     * - description: 이미지 전체 설명
     * - objects: 감지된 주요 객체/사물 목록
     * - mood: 이미지 분위기 또는 톤
     * - detectedText: 이미지 내 텍스트 (OCR 결과)
     * - tags: 검색용 태그 목록
     * 
     * 사용 예시:
     * POST /api/analysis/image
     * Content-Type: multipart/form-data
     * file: [이미지 파일]
     */
    @PostMapping("/image")
    public ResponseEntity<ImageAnalysisService.ImageAnalysisResult> analyzeImage(
            @RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(imageAnalysisService.analyze(file));
    }
}
