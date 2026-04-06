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

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final TokenTextSplitter splitter = new TokenTextSplitter(500, 100, 5, 10000, true);

    public DocumentService(VectorStore vectorStore, DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
    }

    public Document upload(MultipartFile file) {
        Document doc = documentRepository.save(new Document(file.getOriginalFilename(), "PROCESSING"));
        processAsync(doc.getId(), file);
        return doc;
    }

    @Async
    public void processAsync(Long docId, MultipartFile file) {
        Document doc = documentRepository.findById(docId).orElseThrow();
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            List<org.springframework.ai.document.Document> docs;

            if (filename.toLowerCase().endsWith(".pdf")) {
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override public String getFilename() { return filename; }
                };
                DocumentReader reader = new PagePdfDocumentReader(resource,
                        PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
                docs = splitter.apply(reader.get());
            } else {
                // txt, md 등 텍스트 파일
                String text = new String(file.getBytes());
                org.springframework.ai.document.Document textDoc =
                        new org.springframework.ai.document.Document(text);
                docs = splitter.apply(List.of(textDoc));
            }

            vectorStore.add(docs);

            doc.setStatus("DONE");
            doc.setChunkCount(docs.size());
            documentRepository.save(doc);
            log.info("문서 임베딩 완료: {} ({} 청크)", filename, docs.size());

        } catch (Exception e) {
            log.error("문서 처리 실패: {}", e.getMessage());
            doc.setStatus("ERROR");
            documentRepository.save(doc);
        }
    }

    public List<Document> getAll() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }
}
