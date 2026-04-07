package com.chatbot.service;

import com.chatbot.entity.Document;
import com.chatbot.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 문서 업로드 및 벡터 임베딩 서비스
 * PDF, TXT, MD 등의 문서를 업로드하고 파일 시스템에 저장한 후,
 * 텍스트를 추출하여 벡터 스토어(Vector DB)에 임베딩을 저장합니다.
 * 각 청크에 document_id와 file_name 메타데이터를 추가하여 RAG 출처 추적을 지원합니다.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Path uploadDir;  // 파일 저장 디렉토리
    // 텍스트 분할기: 500토큰 청크, 100토큰 오버랩, 최소 5토큰, 최대 10000토큰
    private final TokenTextSplitter splitter = new TokenTextSplitter(500, 100, 5, 10000, true);

    /**
     * 생성자: VectorStore, DocumentRepository 주입 및 업로드 디렉토리 초기화
     * @param vectorStore 벡터 임베딩 저장소 (RAG용)
     * @param documentRepository 문서 메타데이터 저장소
     * @param uploadDir 파일 업로드 디렉토리 경로 (기본값: ./uploads)
     * @throws Exception 디렉토리 생성 실패 시
     */
    public DocumentService(VectorStore vectorStore,
                           DocumentRepository documentRepository,
                           JdbcTemplate jdbcTemplate,
                           @Value("${app.upload-dir:./uploads}") String uploadDir) throws Exception {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(this.uploadDir);  // 디렉토리가 없으면 생성
    }

    /**
     * 문서 업로드
     * 파일을 받아 DB에 메타데이터를 저장하고 비동기로 처리를 시작합니다.
     * 
     * @param file 업로드된 파일 (PDF, TXT, MD 등)
     * @return Document 생성된 문서 엔티티 (상태: PROCESSING)
     */
    public Document upload(MultipartFile file) {
        // 문서 메타데이터를 DB에 저장 (초기 상태: PROCESSING)
        Document doc = documentRepository.save(new Document(file.getOriginalFilename(), "PROCESSING"));
        // 비동기로 문서 처리 시작 (파일 저장 + 임베딩 생성)
        processAsync(doc.getId(), file);
        return doc;
    }

    /**
     * 비동기 문서 처리
     * 파일을 파일 시스템에 저장하고, 타입에 따라 텍스트를 추출한 후,
     * 청크로 분할하여 벡터 스토어에 임베딩을 저장합니다.
     * 
     * @param docId 문서 ID
     * @param file 업로드된 파일
     * 
     * 처리 흐름:
     * 1. 파일을 업로드 디렉토리에 저장 (ID_파일명 형태로 중복 방지)
     * 2. 파일 경로를 DB에 저장
     * 3. 파일 타입 확인 (PDF vs 텍스트)
     * 4. PDF: PagePdfDocumentReader로 페이지별 텍스트 추출
     *    텍스트: 파일 내용을 직접 읽기
     * 5. TokenTextSplitter로 텍스트를 청크로 분할
     * 6. 각 청크에 document_id, file_name 메타데이터 추가 (RAG 출처 추적용)
     * 7. VectorStore에 임베딩 저장 (RAG 검색용)
     * 8. 문서 상태를 DONE으로 업데이트
     * 9. 실패 시 상태를 ERROR로 업데이트
     */
    @Async
    public void processAsync(Long docId, MultipartFile file) {
        // DB에서 문서 엔티티 조회
        Document doc = documentRepository.findById(docId).orElseThrow();
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            byte[] bytes = file.getBytes();

            // 1. 파일을 업로드 디렉토리에 저장 (ID_파일명 형태로 저장하여 중복 방지)
            Path filePath = uploadDir.resolve(docId + "_" + filename);
            Files.write(filePath, bytes);
            // 2. 파일 경로를 DB에 저장 (다운로드용)
            doc.setFilePath(filePath.toString());

            List<org.springframework.ai.document.Document> docs;

            // 3. 파일 타입에 따라 처리 방식 분기
            if (filename.toLowerCase().endsWith(".pdf")) {
                // 4-1. PDF 파일 처리: ByteArrayResource로 변환
                ByteArrayResource resource = new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return filename; }
                };
                // PagePdfDocumentReader: 페이지당 1개 문서로 읽기
                DocumentReader reader = new PagePdfDocumentReader(resource,
                        PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
                // 5. 텍스트를 청크로 분할
                docs = splitter.apply(reader.get());
            } else {
                // 4-2. txt, md 등 텍스트 파일 처리
                String text = new String(bytes);
                org.springframework.ai.document.Document textDoc =
                        new org.springframework.ai.document.Document(text);
                // 5. 텍스트를 청크로 분할
                docs = splitter.apply(List.of(textDoc));
            }

            // 6. 각 청크에 document_id, file_name 메타데이터 추가 (RAG 출처 추적용)
            docs.forEach(d -> {
                d.getMetadata().put("document_id", docId.toString());
                d.getMetadata().put("file_name", filename);
            });

            // 7. 벡터 스토어에 임베딩 저장 (RAG 검색용)
            vectorStore.add(docs);

            // 8. 문서 상태 업데이트: DONE
            doc.setStatus("DONE");
            doc.setChunkCount(docs.size());
            documentRepository.save(doc);
            log.info("문서 임베딩 완료: {} ({} 청크)", filename, docs.size());

        } catch (Exception e) {
            // 9. 에러 발생 시 상태를 ERROR로 업데이트
            log.error("문서 처리 실패: {}", e.getMessage());
            doc.setStatus("ERROR");
            documentRepository.save(doc);
        }
    }

    /**
     * 모든 문서 조회
     * 업로드된 모든 문서를 최신순으로 조회합니다.
     * 
     * @return List<Document> 문서 목록 (최신순)
     */
    public List<Document> getAll() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 문서 파일 경로 조회
     * 문서 ID로 저장된 파일의 경로를 조회합니다 (다운로드용).
     * 
     * @param docId 문서 ID
     * @return Path 파일 경로
     * @throws RuntimeException 파일이 저장되지 않은 경우
     */
    public Path getFilePath(Long docId) {
        Document doc = documentRepository.findById(docId).orElseThrow();
        if (doc.getFilePath() == null) throw new RuntimeException("저장된 파일이 없습니다.");
        return Paths.get(doc.getFilePath());
    }

    /**
     * 문서 삭제
     * 파일, 벡터 스토어 임베딩, DB 메타데이터를 모두 삭제합니다.
     * 
     * @param docId 삭제할 문서 ID
     * 
     * 처리 흐름:
     * 1. DB에서 문서 엔티티 조회
     * 2. 파일 시스템에서 파일 삭제 (실패해도 계속 진행)
     * 3. 벡터 스토어에서 해당 문서의 모든 청크 삭제 (metadata의 document_id로 필터링)
     * 4. DB에서 문서 메타데이터 삭제
     * 5. 삭제 완료 로그 기록
     * 
     * 주의:
     * - 벡터 스토어 삭제는 PostgreSQL의 JSONB 연산자(->>'document_id')를 사용
     * - 파일 삭제 실패는 무시하고 계속 진행 (이미 삭제되었을 수 있음)
     */
    public void delete(Long docId) {
        // 1. DB에서 문서 엔티티 조회
        Document doc = documentRepository.findById(docId).orElseThrow();

        // 2. 파일 시스템에서 파일 삭제 (실패해도 계속 진행)
        if (doc.getFilePath() != null) {
            try { Files.deleteIfExists(Paths.get(doc.getFilePath())); } catch (Exception ignored) {}
        }

        // 3. 벡터 스토어에서 해당 문서의 모든 청크 삭제 (metadata의 document_id로 필터링)
        jdbcTemplate.update(
            "DELETE FROM vector_store WHERE metadata->>'document_id' = ?",
            docId.toString()
        );

        // 4. DB에서 문서 메타데이터 삭제
        documentRepository.deleteById(docId);
        // 5. 삭제 완료 로그 기록
        log.info("문서 삭제 완료: id={}, fileName={}", docId, doc.getFileName());
    }
}
