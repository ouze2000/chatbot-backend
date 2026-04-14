# ── 1단계: 빌드 ────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 의존성 캐시 레이어 (pom.xml이 바뀌지 않으면 재다운로드 생략)
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -Dmaven.test.skip=true -B

# ── 2단계: 실행 ────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
