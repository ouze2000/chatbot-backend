package com.chatbot.config;

import com.chatbot.entity.User;
import com.chatbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 기본 사용자(admin/1234) 생성
 * 이미 존재하는 경우 건너뜁니다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername("admin")) {
            userRepository.save(new User("admin", passwordEncoder.encode("1234"), "ROLE_ADMIN"));
            log.info("기본 관리자 계정 생성 완료: admin");
        }
        if (!userRepository.existsByUsername("ouze")) {
            userRepository.save(new User("ouze", passwordEncoder.encode("1234"), "ROLE_USER"));
            log.info("기본 사용자 계정 생성 완료: ouze");
        }
    }
}
