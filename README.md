# Chatbot Backend

Spring Boot + Spring AI 기반 챗봇 백엔드 샘플 프로젝트

Spring AI의 주요 기능인 **RAG**, **Tool Calling**, **Structured Output**, **Multimodal**을 실제로 구현하여 보여주는 예제입니다.

---

## 구현된 Spring AI 기능

| 기능 | 설명 | 관련 클래스 |
|---|---|---|
| **Chat + Streaming** | SSE 기반 실시간 스트리밍 채팅, 대화 이력 관리 | `ChatService` |
| **RAG** | 문서 임베딩 → pgvector 저장 → 유사도 검색 → 컨텍스트 주입 | `DocumentService`, `ChatService` |
| **Tool Calling** | AI가 필요 시 날씨/날짜/계산 도구를 자동으로 호출 | `WeatherTool`, `DateTimeTool`, `CalculatorTool` |
| **Structured Output** | LLM 응답을 Java 객체로 자동 변환 (`entity()`) | `AnalysisService` |
| **Multimodal** | 이미지 + 텍스트를 함께 Claude Vision에 전달 | `ImageAnalysisService` |

---

## 아키텍처

```
Frontend (React)
     │
     ▼
ChatController ──────────────────────────────────────────┐
     │                                                   │
     ├── VectorStore (pgvector)  ← DocumentService       │
     │     유사 청크 검색 (RAG)       임베딩 저장          │
     │                                                   │
     ├── WeatherTool / DateTimeTool / CalculatorTool      │
     │     Tool Calling (필요 시 자동 호출)               │
     │                                                   │
     └── AnthropicChatModel (Claude)  ──────────────────►┘
           SSE 스트리밍 응답

AnalysisController
     ├── AnalysisService      → Structured Output (텍스트 분석)
     └── ImageAnalysisService → Multimodal + Structured Output (이미지 분석)
```

---

## 기술 스택

- Java 17
- Spring Boot 3.3.5
- Spring AI 1.0.5
  - Anthropic Claude (`claude-sonnet-4-6`) — 채팅, Tool Calling, Vision
  - OpenAI (`text-embedding-3-small`) — 텍스트 임베딩
  - pgvector — 벡터 스토어
- PostgreSQL 16 + pgvector
- JPA / Hibernate

---

## 사전 요구사항

- Java 17 이상
- Maven 3.x
- Docker Desktop

---

## 1. API 키 준비

| 키 | 용도 | 발급처 |
|---|---|---|
| `ANTHROPIC_API_KEY` | Claude 채팅 / Vision 모델 | https://console.anthropic.com |
| `OPENAI_API_KEY` | 텍스트 임베딩 (text-embedding-3-small) | https://platform.openai.com |
| `app.weather-api-key` | 날씨 Tool (OpenWeatherMap) | https://openweathermap.org/api |

---

## 2. PostgreSQL + pgvector 설치 (Docker)

`pgvector/pgvector:pg16` 이미지를 사용하며, **pgvector 확장이 미리 포함**되어 있어 별도 설치가 필요 없습니다.

```bash
# 프로젝트 루트에서 실행
docker-compose up -d
```

컨테이너가 정상 기동되면 아래 정보로 접속할 수 있습니다.

| 항목 | 값 |
|---|---|
| Host | localhost:5432 |
| Database | chatbot |
| User | chatbot |
| Password | chatbot1234 |

### pgvector 확장 활성화 확인

애플리케이션 기동 시 `spring.ai.vectorstore.pgvector.initialize-schema=true` 설정으로
`vector_store` 테이블과 pgvector 확장이 자동 생성됩니다.

수동으로 확인하려면:

```bash
# PostgreSQL 접속
docker exec -it chatbot-db psql -U chatbot -d chatbot

# pgvector 확장 확인
SELECT * FROM pg_extension WHERE extname = 'vector';

# vector_store 테이블 확인 (앱 기동 후)
\d vector_store
```

### Docker 컨테이너 관리

```bash
# 시작
docker-compose up -d

# 중지
docker-compose stop

# 중지 + 컨테이너 삭제 (데이터 볼륨은 유지)
docker-compose down

# 중지 + 컨테이너 + 데이터 볼륨 전체 삭제
docker-compose down -v
```

---

## 3. 로컬 환경 설정

`src/main/resources/application-local.yml` 파일을 생성하고 API 키를 입력합니다.

```yaml
spring:
  ai:
    anthropic:
      api-key: sk-ant-api03-...   # Anthropic API 키
    openai:
      api-key: sk-proj-...        # OpenAI API 키

app:
  weather-api-key: ...            # OpenWeatherMap API 키
```

> `application-local.yml`은 `.gitignore`에 포함되어 있어 Git에 커밋되지 않습니다.

---

## 4. 애플리케이션 기동

```bash
# local 프로파일로 실행 (application-local.yml 적용)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

기동 후 `http://localhost:8081` 에서 API를 사용할 수 있습니다.

---

## 5. 주요 API

### 채팅
| Method | URL | 설명 |
|---|---|---|
| POST | `/api/chat/stream` | SSE 스트리밍 채팅 (RAG + Tool Calling) |

### 문서 관리
| Method | URL | 설명 |
|---|---|---|
| GET | `/api/documents` | 문서 목록 조회 |
| POST | `/api/documents/upload` | 문서 업로드 및 임베딩 (PDF, TXT, MD) |
| DELETE | `/api/documents/{id}` | 문서 삭제 (파일 + 벡터 + DB) |
| GET | `/api/documents/{id}/download` | 문서 다운로드 |

### 분석
| Method | URL | 설명 |
|---|---|---|
| POST | `/api/analysis` | 텍스트 분석 (Structured Output) |
| POST | `/api/analysis/image` | 이미지 분석 (Multimodal + Structured Output) |

---

## 6. 업로드 파일 저장 위치

업로드된 파일은 기본적으로 `./uploads/` 디렉토리에 저장됩니다.
경로를 변경하려면 `application.yml` 또는 `application-local.yml`에 아래 설정을 추가합니다.

```yaml
app:
  upload-dir: /your/custom/path
```
