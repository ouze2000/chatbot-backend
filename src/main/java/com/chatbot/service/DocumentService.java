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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 문서 업로드 및 벡터 임베딩 서비스
 * PDF, TXT, MD 등의 문서를 업로드하고 텍스트를 추출하여
 * 벡터 스토어(Vector DB)에 임베딩을 저장합니다.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    // 텍스트 분할기: 500토큰 청크, 100토큰 오버랩, 최소 5토큰, 최대 10000토큰
    private final TokenTextSplitter splitter = new TokenTextSplitter(500, 100, 5, 10000, true);

    /**
     * 생성자: VectorStore와 DocumentRepository 주입
     * @param vectorStore 벡터 임베딩 저장소 (RAG용)
     * @param documentRepository 문서 메타데이터 저장소
     */
    public DocumentService(VectorStore vectorStore, DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
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
        // 비동기로 문서 처리 시작 (임베딩 생성)
        processAsync(doc.getId(), file);
        return doc;
    }

    /**
     * 비동기 문서 처리
     * 파일 타입에 따라 텍스트를 추출하고, 청크로 분할한 후 벡터 스토어에 임베딩을 저장합니다.
     * 
     * @param docId 문서 ID
     * @param file 업로드된 파일
     * 처리 흐름:
     * 1. 파일 타입 확인 (PDF vs 텍스트)
     * 2. PDF: PagePdfDocumentReader로 페이지별 텍스트 추출
     *    텍스트: 파일 내용을 직접 읽기
     * 3. TokenTextSplitter로 텍스트를 청크로 분할
     * 4. VectorStore에 임베딩 저장 (RAG 검색용)
     * 5. 문서 상태를 DONE으로 업데이트
     * 6. 실패 시 상태를 ERROR로 업데이트
     */
    @Async
    public void processAsync(Long docId, MultipartFile file) {
        // DB에서 문서 엔티티 조회
        Document doc = documentRepository.findById(docId).orElseThrow();
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            List<org.springframework.ai.document.Document> docs;

            // 파일 타입에 따라 처리 방식 분기
            if (filename.toLowerCase().endsWith(".pdf")) {
                // PDF 파일 처리: ByteArrayResource로 변환
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override public String getFilename() { return filename; }
                };
                // PagePdfDocumentReader: 페이지당 1개 문서로 읽기
                DocumentReader reader = new PagePdfDocumentReader(resource,
                        PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
                // 텍스트를 청크로 분할
                docs = splitter.apply(reader.get());
            } else {
                // txt, md 등 텍스트 파일 처리
                String text = new String(file.getBytes());
                org.springframework.ai.document.Document textDoc =
                        new org.springframework.ai.document.Document(text);
                // 텍스트를 청크로 분할
                docs = splitter.apply(List.of(textDoc));
            }

            // 벡터 스토어에 임베딩 저장 (RAG 검색용)
            vectorStore.add(docs);

            // 문서 상태 업데이트: DONE
            doc.setStatus("DONE");
            doc.setChunkCount(docs.size());
            documentRepository.save(doc);
            log.info("문서 임베딩 완료: {} ({} 청크)", filename, docs.size());

        } catch (Exception e) {
            // 에러 발생 시 상태를 ERROR로 업데이트
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
}
