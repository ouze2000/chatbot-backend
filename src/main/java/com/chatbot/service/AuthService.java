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
 * 인증 서비스
 * JWT 기반 로그인, 토큰 재발급, 로그아웃 비즈니스 로직을 처리합니다.
 * 액세스 토큰은 JWT로 생성하고, 리프레시 토큰은 UUID로 생성하여 DB에 저장합니다.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long refreshTokenExpiry;  // 리프레시 토큰 만료 시간 (ms)

    /**
     * 생성자: 필요한 Repository, Service, Encoder 주입
     * @param userRepository 사용자 저장소
     * @param refreshTokenRepository 리프레시 토큰 저장소
     * @param jwtService JWT 토큰 생성/검증 서비스
     * @param passwordEncoder 비밀번호 암호화/검증용 (BCrypt)
     * @param refreshTokenExpiry 리프레시 토큰 만료 시간 (application.yml에서 설정)
     */
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
     * 로그인 처리
     * 
     * @param request 로그인 요청 (username, password)
     * @return TokenResponse 액세스 토큰과 리프레시 토큰
     * 
     * 처리 흐름:
     * 1. username으로 사용자 조회
     * 2. 비밀번호 검증 (BCrypt로 암호화된 비밀번호와 비교)
     * 3. 기존 리프레시 토큰 삭제 (단일 세션 유지)
     * 4. 새 액세스 토큰 생성 (JWT, 15분 유효)
     * 5. 새 리프레시 토큰 생성 (UUID, 7일 유효) 및 DB 저장
     * 6. 두 토큰을 TokenResponse로 반환
     * 
     * 보안 특징:
     * - 비밀번호는 BCrypt로 암호화되어 DB에 저장
     * - 로그인 실패 시 username/password 구분 없이 동일한 에러 메시지 반환 (보안 강화)
     * - 기존 리프레시 토큰 삭제로 단일 세션 유지 (동시 로그인 방지)
     * 
     * @throws IllegalArgumentException 사용자를 찾을 수 없거나 비밀번호가 일치하지 않을 때
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        // 1. username으로 사용자 조회
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));

        // 2. 비밀번호 검증 (BCrypt)
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 3. 기존 리프레시 토큰 삭제 (단일 세션 유지)
        refreshTokenRepository.deleteByUsernameDirectly(user.getUsername());

        // 4. 새 액세스 토큰 생성 (JWT, 15분 유효)
        String accessToken = jwtService.generateAccessToken(user.getUsername(), user.getRole());
        // 5. 새 리프레시 토큰 생성 (UUID, 7일 유효) 및 DB 저장
        String refreshToken = createRefreshToken(user.getUsername());

        // 6. 두 토큰 반환
        return new TokenResponse(accessToken, refreshToken);
    }

    /**
     * 액세스 토큰 재발급
     * 
     * @param refreshToken 리프레시 토큰
     * @return TokenResponse 새 액세스 토큰과 기존 리프레시 토큰
     * 
     * 처리 흐름:
     * 1. DB에서 리프레시 토큰 조회
     * 2. 리프레시 토큰 만료 여부 확인
     * 3. 만료되었으면 DB에서 삭제 후 에러 반환
     * 4. 리프레시 토큰에서 username 추출
     * 5. username으로 사용자 조회
     * 6. 새 액세스 토큰 생성 (JWT, 15분 유효)
     * 7. 새 액세스 토큰과 기존 리프레시 토큰 반환
     * 
     * 주의사항:
     * - 리프레시 토큰은 교체하지 않음 (Rotation 제거)
     * - Rotation 제거 이유: 멀티 탭 환경에서 동시 요청 시 race condition 방지
     * - 리프레시 토큰은 7일 동안 유효하며, 만료 시 재로그인 필요
     * 
     * @throws IllegalArgumentException 리프레시 토큰이 유효하지 않거나 만료되었거나 사용자를 찾을 수 없을 때
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        // 1. DB에서 리프레시 토큰 조회
        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다."));

        // 2-3. 리프레시 토큰 만료 여부 확인
        if (stored.isExpired()) {
            refreshTokenRepository.delete(stored);
            throw new IllegalArgumentException("리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        }

        // 4. 리프레시 토큰에서 username 추출
        String username = stored.getUsername();
        // 5. username으로 사용자 조회
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 6. 새 액세스 토큰 생성 (JWT, 15분 유효)
        // 리프레시 토큰은 그대로 유지 (Rotation 제거 — 멀티 탭 동시 요청 시 race condition 방지)
        String newAccessToken = jwtService.generateAccessToken(username, user.getRole());

        // 7. 새 액세스 토큰과 기존 리프레시 토큰 반환
        return new TokenResponse(newAccessToken, refreshToken);
    }

    /**
     * 로그아웃 처리
     * 
     * @param refreshToken 리프레시 토큰
     * 
     * 처리 흐름:
     * 1. DB에서 리프레시 토큰 삭제
     * 2. 삭제된 토큰은 재사용 불가
     * 
     * 보안 특징:
     * - DB에서 리프레시 토큰을 삭제하여 토큰 재사용 방지
     * - 클라이언트는 쿠키와 메모리의 액세스 토큰도 삭제해야 함
     * - @Transactional로 원자성 보장
     */
    @Transactional
    public void logout(String refreshToken) {
        // DB에서 리프레시 토큰 삭제
        refreshTokenRepository.deleteByTokenDirectly(refreshToken);
    }

    /**
     * 리프레시 토큰 생성 및 DB 저장
     * 
     * @param username 사용자명
     * @return String 생성된 리프레시 토큰 (UUID)
     * 
     * 처리 흐름:
     * 1. UUID로 랜덤 토큰 생성
     * 2. 현재 시각 + refreshTokenExpiry로 만료 시각 계산 (7일)
     * 3. RefreshToken 엔티티 생성 및 DB 저장
     * 4. 생성된 토큰 반환
     * 
     * 토큰 형식:
     * - UUID v4 형식 (36자, 하이픈 포함)
     * - 예시: "550e8400-e29b-41d4-a716-446655440000"
     */
    private String createRefreshToken(String username) {
        // 1. UUID로 랜덤 토큰 생성
        String token = UUID.randomUUID().toString();
        // 2. 만료 시각 계산 (7일)
        Instant expiresAt = Instant.now().plusMillis(refreshTokenExpiry);
        // 3. DB 저장
        refreshTokenRepository.save(new RefreshToken(token, username, expiresAt));
        // 4. 토큰 반환
        return token;
    }
}
