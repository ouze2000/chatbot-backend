package com.chatbot.repository;

import com.chatbot.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    // 직접 DELETE 쿼리 — 영향받은 행 수 반환, 동시 요청 시 충돌 없음
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshToken r WHERE r.token = :token")
    int deleteByTokenDirectly(@Param("token") String token);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshToken r WHERE r.username = :username")
    void deleteByUsernameDirectly(@Param("username") String username);
}
