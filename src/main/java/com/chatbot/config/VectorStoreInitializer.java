package com.chatbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 벡터 스토어 FTS 인덱스 초기화
 * 애플리케이션 준비 완료 후 PostgreSQL Full Text Search용 GIN 인덱스를 생성합니다.
 * Spring AI가 vector_store 테이블을 먼저 생성한 뒤 실행되도록 ApplicationReadyEvent를 사용합니다.
 */
@Component
public class VectorStoreInitializer {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * GIN 인덱스 생성
     * - vector_store.content 컬럼에 Full Text Search용 GIN 인덱스 생성
     * - 'simple' 설정: 언어 중립적 토크나이저 (한국어/영어 모두 지원)
     * - IF NOT EXISTS: 재시작 시 중복 생성 방지
     */
    @EventListener(ApplicationReadyEvent.class)
    public void createFtsIndex() {
        try {
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS vector_store_content_fts_idx
                    ON vector_store USING GIN (to_tsvector('simple', content))
                    """);
            log.info("FTS GIN index ready on vector_store.content");
        } catch (Exception e) {
            log.warn("Failed to create FTS GIN index: {}", e.getMessage());
        }
    }
}
