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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final long refreshTokenExpiry;

    public AuthController(AuthService authService,
                          @Value("${app.jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.authService = authService;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    /** 로그인 — 액세스 토큰은 body, 리프레시 토큰은 httpOnly 쿠키로 발급 */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequest request,
            HttpServletResponse response) {
        var tokens = authService.login(request);
        setRefreshCookie(response, tokens.refreshToken());
        return ResponseEntity.ok(Map.of(
                "accessToken", tokens.accessToken(),
                "tokenType", tokens.tokenType()
        ));
    }

    /** 액세스 토큰 재발급 — 리프레시 토큰은 쿠키에서 읽음 (토큰 자체는 교체하지 않음) */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).body(Map.of("message", "리프레시 토큰이 없습니다."));
        }
        var tokens = authService.refresh(refreshToken);
        return ResponseEntity.ok(Map.of(
                "accessToken", tokens.accessToken(),
                "tokenType", tokens.tokenType()
        ));
    }

    /** 로그아웃 — 쿠키에서 리프레시 토큰 읽어 삭제, 쿠키 만료 처리 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        clearRefreshCookie(response);
        return ResponseEntity.ok(Map.of("message", "로그아웃 되었습니다."));
    }

    // httpOnly 쿠키 설정 — /api/auth 경로에만 전송
    private void setRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", token)
                .httpOnly(true)
                .secure(false)          // 운영 환경(HTTPS)에서는 true로 변경
                .path("/api/auth")
                .maxAge(Duration.ofMillis(refreshTokenExpiry))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    // 쿠키 삭제 (maxAge=0)
    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(false)
                .path("/api/auth")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
