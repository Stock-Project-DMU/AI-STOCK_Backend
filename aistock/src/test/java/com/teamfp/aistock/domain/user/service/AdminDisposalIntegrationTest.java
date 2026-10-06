package com.teamfp.aistock.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.admin.service.AuditLogService;
import com.teamfp.aistock.domain.user.dto.request.UserWithdrawalRequest;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * 관리자 계정 폐기(feat/admin-improvements) — 비밀번호 + 관리자 인증 코드가 맞으면 폐기되고 감사 로그(ADMIN_DISPOSE)가
 * 남는다. 인증 코드는 이 테스트에서만 정한 값을 쓴다. @Transactional로 넣은 행은 롤백된다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "admin.signup-code=TEST-ADMIN-CODE")
class AdminDisposalIntegrationTest {

    @Autowired
    private UserWithdrawalService userWithdrawalService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("비밀번호와 관리자 인증 코드가 맞으면 관리자 계정이 폐기되고 감사 로그가 남는다")
    void disposesAdminWithCorrectCode() {
        User admin = userRepository.saveAndFlush(User.builder().loginId("dispose" + System.nanoTime()).name("폐기관리자")
                .password(passwordEncoder.encode("testpass123")).role(Role.ADMIN).isActive(true).build());

        userWithdrawalService.withdraw(admin.getUserId(), new UserWithdrawalRequest("testpass123", null, "TEST-ADMIN-CODE"));
        entityManager.clear();

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().isActive()).isFalse();
        assertThat(auditLogRepository.findFirstByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                AuditLogService.ACTION_ADMIN_DISPOSE, AuditLogService.TARGET_ADMIN, admin.getUserId())).isPresent();
    }
}
