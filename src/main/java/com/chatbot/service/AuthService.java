package com.chatbot.service;

import com.chatbot.dto.LoginRequest;
import com.chatbot.dto.TokenResponse;
import com.chatbot.entity.RefreshToken;
import com.chatbot.entity.User;
import com.chatbot.repository.RefreshTokenRepository;
import com.chatbot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 로그인/토큰 재발급/로그아웃 처리 서비스
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long refreshTokenExpiry;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    /**
     * 로그인 — username/password 검증 후 액세스/리프레시 토큰 발급
     * 기존 리프레시 토큰은 삭제하고 새로 발급 (단일 세션)
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 기존 리프레시 토큰 삭제 (단일 세션 유지)
        refreshTokenRepository.deleteByUsername(user.getUsername());

        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        String refreshToken = createRefreshToken(user.getUsername());

        return new TokenResponse(accessToken, refreshToken);
    }

    /**
     * 리프레시 토큰으로 액세스 토큰 재발급
     * 리프레시 토큰도 함께 교체 (Refresh Token Rotation)
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다."));

        if (stored.isExpired()) {
            refreshTokenRepository.delete(stored);
            throw new IllegalArgumentException("리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        }

        String username = stored.getUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 기존 리프레시 토큰 삭제 후 새로 발급 (Rotation)
        refreshTokenRepository.delete(stored);

        String newAccessToken = jwtService.generateAccessToken(username, user.getRole());
        String newRefreshToken = createRefreshToken(username);

        return new TokenResponse(newAccessToken, newRefreshToken);
    }

    /**
     * 로그아웃 — 리프레시 토큰 삭제
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByToken(refreshToken);
    }

    private String createRefreshToken(String username) {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusMillis(refreshTokenExpiry);
        refreshTokenRepository.save(new RefreshToken(token, username, expiresAt));
        return token;
    }
}
