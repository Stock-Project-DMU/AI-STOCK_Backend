package com.teamfp.aistock.domain.user.service;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.domain.user.dto.request.UserWithdrawalRequest;
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
        // 배당 권리는 account_id에 FK가 없어 계좌를 지워도 남던 데이터 — 탈퇴 때 함께 지워져야 한다(2026-10-13).
        Long accountId = accounts.findAllByUserIdForUpdate(user.getUserId()).get(0).getAccountId();
        entityManager.createNativeQuery("insert into dividend_schedules (stock_code, fiscal_year, period, created_at, updated_at)"
                        + " values ('999999', '2099', 'Q4', now(), now())")
                .executeUpdate();
        Object scheduleId = entityManager.createNativeQuery("select last_insert_id()").getSingleResult();
        entityManager.createNativeQuery("insert into dividend_entitlements (account_id, dividend_schedule_id, stock_code, quantity, dps_cash,"
                        + " total_amount, status, ex_dividend_date, created_at) values (:accountId, :scheduleId, '999999', 1, 100, 100, 'PENDING', '2099-12-30', now())")
                .setParameter("accountId", accountId).setParameter("scheduleId", scheduleId).executeUpdate();
        withdrawal.withdraw(user.getUserId(), new UserWithdrawalRequest("testpass123", null));
        entityManager.clear();
        var deactivated = users.findById(user.getUserId()).orElseThrow();
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getName()).isEqualTo("탈퇴회원");
        assertThat(deactivated.getPassword()).isNull();
        assertThat(deactivated.getEmail()).isNull();
        assertThat(accounts.count()).isEqualTo(beforeAccounts);
        assertThat(goals.findTop100ByUserIdOrderByCreatedAtDesc(user.getUserId())).isEmpty();
        assertThat(((Number) entityManager.createNativeQuery("select count(*) from dividend_entitlements where account_id = :accountId")
                .setParameter("accountId", accountId).getSingleResult()).longValue()).isZero();
    }
}
