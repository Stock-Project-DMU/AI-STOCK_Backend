package com.teamfp.aistock.domain.user.service;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.domain.user.dto.request.PasswordVerifyRequest;
import com.teamfp.aistock.domain.account.service.AccountService;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.ai.repository.GoalPlanRepository;
import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import com.teamfp.aistock.domain.ai.dto.request.GoalPlanRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.*;

/** 임시 사용자의 관계 삭제와 익명화를 실제 DB에서 검증하며 전부 롤백한다. */
@SpringBootTest @Transactional
class UserWithdrawalIntegrationTest {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired GoalPlanRepository goals;
    @Autowired AccountService accountService;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;

    @Test void deletesOnlyOwnChildrenAndAnonymizesUser() {
        var user = users.saveAndFlush(User.builder().loginId("withdraw-test-" + System.nanoTime())
                .name("탈퇴 통합 테스트").password(encoder.encode("testpass123")).isActive(true).build());
        long beforeAccounts = accounts.count();
        accountService.createAccount(user.getUserId(), new CreateAccountRequest("롤백 테스트 계좌"));
        goals.saveAndFlush(GoalPlan.from(user.getUserId(), new GoalPlanRequest("house", 100_000, 5, 0, false)));
        withdrawal.withdraw(user.getUserId(), new PasswordVerifyRequest("testpass123"));
        entityManager.clear();
        var deactivated = users.findById(user.getUserId()).orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getName()).isEqualTo("탈퇴회원");
        assertThat(deactivated.getPassword()).isNull();
        assertThat(deactivated.getEmail()).isNull();
        assertThat(accounts.count()).isEqualTo(beforeAccounts);
        assertThat(goals.findTop100ByUserIdOrderByCreatedAtDesc(user.getUserId())).isEmpty();
    }
}
