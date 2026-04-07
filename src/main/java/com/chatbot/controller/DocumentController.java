package com.chatbot.controller;

import com.chatbot.entity.Document;
import com.chatbot.service.DocumentService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 문서 컨트롤러
 * 문서 업로드, 목록 조회, 다운로드 API를 제공합니다.
 * 업로드된 문서는 벡터 임베딩으로 변환되어 RAG 검색에 사용됩니다.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 생성자: DocumentService 주입
     * @param documentService 문서 서비스
     */
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 문서 업로드 엔드포인트
     * 
     * @param file 업로드할 파일 (PDF, TXT, MD 등)
     * @return ResponseEntity<Document> 생성된 문서 엔티티 (상태: PROCESSING)
     * 
     * 처리 흐름:
     * 1. 파일을 받아 DB에 메타데이터 저장
     * 2. 비동기로 파일 저장 및 벡터 임베딩 생성 시작
     * 3. 초기 상태(PROCESSING)의 문서 엔티티 반환
     * 4. 백그라운드에서 처리 완료 시 상태가 DONE으로 변경됨
     */
    @PostMapping("/upload")
    public ResponseEntity<Document> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(documentService.upload(file));
    }

    /**
     * 모든 문서 목록 조회 엔드포인트
     * 
     * @return ResponseEntity<List<Document>> 문서 목록 (최신순)
     * 
     * 반환 데이터:
     * - id: 문서 ID
     * - fileName: 파일명
     * - status: 처리 상태 (PROCESSING, DONE, ERROR)
     * - chunkCount: 생성된 청크 수
     * - createdAt: 업로드 시간
     */
    @GetMapping
    public ResponseEntity<List<Document>> getAll() {
        return ResponseEntity.ok(documentService.getAll());
    }

    /**
     * 문서 다운로드 엔드포인트
     * 
     * @param id 문서 ID
     * @return ResponseEntity<Resource> 파일 리소스
     * 
     * 처리 흐름:
     * 1. 문서 ID로 파일 경로 조회
     * 2. PathResource로 파일 리소스 생성
     * 3. 파일명을 UTF-8로 인코딩 (한글 파일명 지원)
     * 4. Content-Disposition 헤더로 다운로드 파일명 지정
     * 5. APPLICATION_OCTET_STREAM으로 파일 전송
     */
    /**
     * 문서 삭제 엔드포인트
     * 
     * @param id 삭제할 문서 ID
     * @return ResponseEntity<Void> 204 No Content
     * 
     * 처리 흐름:
     * 1. DocumentService.delete() 호출
     * 2. 파일 시스템, 벡터 스토어, DB에서 문서 삭제
     * 3. 204 No Content 응답 반환
     * 
     * 주의:
     * - 삭제는 되돌릴 수 없음
     * - 벡터 스토어의 모든 관련 임베딩도 함께 삭제됨
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        // 문서 ID로 파일 경로 조회
        Path filePath = documentService.getFilePath(id);
        Resource resource = new PathResource(filePath);

        // 파일명을 UTF-8로 인코딩 (한글 파일명 지원)
        String encodedName = URLEncoder.encode(resource.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        // 파일 다운로드 응답 생성
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }
}
