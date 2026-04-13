package com.chatbot.controller;

import com.chatbot.dto.LoginRequest;
import com.chatbot.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * 인증 컨트롤러
 * JWT 기반 로그인, 토큰 갱신, 로그아웃 API를 제공합니다.
 * 액세스 토큰은 응답 본문으로, 리프레시 토큰은 httpOnly 쿠키로 발급하여 보안성을 향상시킵니다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final long refreshTokenExpiry;  // 리프레시 토큰 만료 시간 (ms)
    private final boolean cookieSecure;     // HTTPS 환경에서만 쿠키 전송 여부
    private final String cookieSameSite;    // CSRF 방지를 위한 SameSite 정책 (Lax/Strict/None)

    /**
     * 생성자: AuthService와 JWT 쿠키 설정값 주입
     * @param authService 인증 서비스
     * @param refreshTokenExpiry 리프레시 토큰 만료 시간 (application.yml에서 설정)
     * @param cookieSecure HTTPS 환경에서만 쿠키 전송 여부 (application.yml에서 설정)
     * @param cookieSameSite SameSite 정책 (Lax/Strict/None, application.yml에서 설정)
     */
    public AuthController(AuthService authService,
                          @Value("${app.jwt.refresh-token-expiry}") long refreshTokenExpiry,
                          @Value("${app.jwt.cookie-secure}") boolean cookieSecure,
                          @Value("${app.jwt.cookie-same-site}") String cookieSameSite) {
        this.authService = authService;
        this.refreshTokenExpiry = refreshTokenExpiry;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    /**
     * 로그인 엔드포인트
     * 
     * @param request 로그인 요청 (username, password)
     * @param response HTTP 응답 (쿠키 설정용)
     * @return ResponseEntity 액세스 토큰과 토큰 타입
     * 
     * 처리 흐름:
     * 1. AuthService.login()으로 사용자 인증 및 토큰 생성
     * 2. 리프레시 토큰을 httpOnly 쿠키로 설정 (XSS 공격 방지)
     * 3. 액세스 토큰은 응답 본문으로 반환 (클라이언트가 메모리에 저장)
     * 
     * 보안 특징:
     * - 리프레시 토큰: httpOnly 쿠키 (JavaScript로 접근 불가, XSS 방지)
     * - 액세스 토큰: 짧은 만료 시간 (15분), Authorization 헤더로 전송
     * - 쿠키 경로: /api/auth로 제한 (불필요한 요청에 토큰 노출 방지)
     * 
     * 반환 데이터:
     * - accessToken: 액세스 토큰 (15분 유효)
     * - tokenType: 토큰 타입 ("Bearer")
     * 
     * 사용 예시:
     * POST /api/auth/login
     * Body: {"username": "user", "password": "pass"}
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequest request,
            HttpServletResponse response) {
        // 1. 사용자 인증 및 토큰 생성
        var tokens = authService.login(request);
        // 2. 리프레시 토큰을 httpOnly 쿠키로 설정
        setRefreshCookie(response, tokens.refreshToken());
        // 3. 액세스 토큰은 응답 본문으로 반환
        return ResponseEntity.ok(Map.of(
                "accessToken", tokens.accessToken(),
                "tokenType", tokens.tokenType()
        ));
    }

    /**
     * 액세스 토큰 재발급 엔드포인트
     * 
     * @param refreshToken 쿠키에서 자동 추출된 리프레시 토큰
     * @return ResponseEntity 새로운 액세스 토큰과 토큰 타입
     * 
     * 처리 흐름:
     * 1. 쿠키에서 리프레시 토큰 추출 (@CookieValue가 자동 처리)
     * 2. 리프레시 토큰이 없으면 401 에러 반환
     * 3. AuthService.refresh()로 리프레시 토큰 검증 및 새 액세스 토큰 발급
     * 4. 새 액세스 토큰을 응답 본문으로 반환
     * 
     * 주의사항:
     * - 리프레시 토큰 자체는 교체하지 않음 (기존 쿠키 유지)
     * - 리프레시 토큰은 7일 동안 유효
     * - 액세스 토큰 만료 시 자동으로 이 엔드포인트 호출
     * 
     * 반환 데이터:
     * - accessToken: 새로운 액세스 토큰 (15분 유효)
     * - tokenType: 토큰 타입 ("Bearer")
     * 
     * 사용 예시:
     * POST /api/auth/refresh
     * Cookie: refreshToken=...
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        // 1-2. 쿠키에서 리프레시 토큰 확인
        if (refreshToken == null) {
            return ResponseEntity.status(401).body(Map.of("message", "리프레시 토큰이 없습니다."));
        }
        // 3. 리프레시 토큰 검증 및 새 액세스 토큰 발급
        var tokens = authService.refresh(refreshToken);
        // 4. 새 액세스 토큰 반환
        return ResponseEntity.ok(Map.of(
                "accessToken", tokens.accessToken(),
                "tokenType", tokens.tokenType()
        ));
    }

    /**
     * 로그아웃 엔드포인트
     * 
     * @param refreshToken 쿠키에서 자동 추출된 리프레시 토큰
     * @param response HTTP 응답 (쿠키 삭제용)
     * @return ResponseEntity 로그아웃 성공 메시지
     * 
     * 처리 흐름:
     * 1. 쿠키에서 리프레시 토큰 추출
     * 2. 리프레시 토큰이 있으면 DB에서 삭제 (AuthService.logout())
     * 3. 쿠키 만료 처리 (maxAge=0으로 설정)
     * 4. 로그아웃 성공 메시지 반환
     * 
     * 보안 특징:
     * - 리프레시 토큰을 DB에서 삭제하여 재사용 불가
     * - 쿠키를 명시적으로 만료 처리하여 클라이언트에서 제거
     * - 클라이언트는 메모리에 저장된 액세스 토큰도 삭제해야 함
     * 
     * 사용 예시:
     * POST /api/auth/logout
     * Cookie: refreshToken=...
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        // 1-2. 리프레시 토큰이 있으면 DB에서 삭제
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        // 3. 쿠키 만료 처리
        clearRefreshCookie(response);
        // 4. 로그아웃 성공 메시지 반환
        return ResponseEntity.ok(Map.of("message", "로그아웃 되었습니다."));
    }

    /**
     * 리프레시 토큰을 httpOnly 쿠키로 설정
     * 
     * @param response HTTP 응답
     * @param token 리프레시 토큰
     * 
     * 쿠키 설정:
     * - httpOnly: true (JavaScript로 접근 불가, XSS 공격 방지)
     * - secure: HTTPS 환경에서만 전송 (application.yml에서 설정)
     * - path: /api/auth (인증 관련 요청에만 쿠키 전송)
     * - maxAge: 7일 (application.yml에서 설정)
     * - sameSite: Lax/Strict/None (CSRF 공격 방지, application.yml에서 설정)
     */
    private void setRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", token)
                .httpOnly(true)                                  // JavaScript로 접근 불가
                .secure(cookieSecure)                            // HTTPS 환경에서만 전송
                .path("/api/auth")                              // 인증 관련 요청에만 쿠키 전송
                .maxAge(Duration.ofMillis(refreshTokenExpiry))  // 7일 동안 유효
                .sameSite(cookieSameSite)                        // CSRF 공격 방지
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 리프레시 토큰 쿠키 삭제
     * 
     * @param response HTTP 응답
     * 
     * 쿠키 삭제 방법:
     * - maxAge=0으로 설정하여 브라우저에서 즉시 삭제
     * - 다른 설정(httpOnly, secure, path, sameSite)은 설정 시와 동일하게 유지
     * - 빈 문자열로 토큰 값 대체
     */
    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")  // 빈 문자열로 대체
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/auth")
                .maxAge(0)                                               // 즉시 만료
                .sameSite(cookieSameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
