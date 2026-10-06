package com.teamfp.aistock.domain.admin.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityType;
import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.admin.repository.AdminActivityRepository.ActivityKey;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminActivityRepository의 네이티브 UNION 쿼리를 실제 MySQL로 검증한다(feat/admin-improvements) — 모든 갈래의 SQL이
 * 문법 오류 없이 돌고, 한 가지 일이 한 줄로만 나오며(충전 승인 입금·승인 감사 로그는 따로 안 나옴), 회원 검색·기간
 * 필터가 걸리는지. 사전 조건: 로컬 MySQL이 떠 있어야 한다. @Transactional로 넣은 행은 롤백된다 — 로컬 DB의 기존
 * 데이터와 섞이지 않도록 이 테스트가 만든 회원 아이디로 정확히 일치 검색해서 검증한다.
 */
@SpringBootTest
@Transactional
class AdminActivityRepositoryIntegrationTest {

    @Autowired
    private AdminActivityRepository adminActivityRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private ChargeRequestRepository chargeRequestRepository;
    @Autowired
    private AccountTransactionRepository accountTransactionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private EntityManager entityManager;

    private static final List<AdminActivityType> ALL = List.of(AdminActivityType.values());

    @Test
    @DisplayName("전체 갈래가 실행되고, 충전 요청은 승인 입금·감사 로그와 합쳐 한 줄만, 셀프 충전은 따로 한 줄 나온다")
    void oneRowPerEventAcrossAllBranches() {
        String loginId = "act" + System.nanoTime();
        User user = userRepository.save(User.builder().loginId(loginId).name("활동테스터").role(Role.USER).isActive(true).build());
        User admin = userRepository.save(User.builder().loginId("adm" + System.nanoTime()).name("관리자").role(Role.ADMIN)
                .isActive(true).build());
        Account account = accountRepository.save(Account.builder().user(user).accountName("계좌").accountNumber("A" + (System.nanoTime() % 10_000_000))
                .openedAt(LocalDate.now()).baseBalance(1_000_000L).balance(1_000_000L).build());
        ChargeRequest request = chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(5_000L).reason("요청").build());
        request.approve(admin, "승인");
        accountTransactionRepository.save(AccountTransaction.builder().account(account).type(AccountTransactionType.ADMIN_CHARGE)
                .amount(5_000L).balanceBefore(1_000_000L).balanceAfter(1_005_000L).relatedChargeRequestId(request.getRequestId())
                .processedBy(admin.getUserId()).build());
        accountTransactionRepository.save(AccountTransaction.builder().account(account).type(AccountTransactionType.AUTO_CHARGE)
                .amount(1_000L).balanceBefore(1_005_000L).balanceAfter(1_006_000L).build());
        auditLogRepository.save(AuditLog.builder().adminUserId(admin.getUserId()).adminLoginId(admin.getLoginId())
                .action("CHARGE_REQUEST_DECISION").targetType("CHARGE_REQUEST").targetId(request.getRequestId()).build());
        auditLogRepository.save(AuditLog.builder().adminUserId(admin.getUserId()).adminLoginId(admin.getLoginId())
                .action("ACCOUNT_STATUS_CHANGE").targetType("ACCOUNT").targetId(account.getAccountId())
                .beforeValue("ACTIVE").afterValue("SUSPENDED").build());
        entityManager.flush();

        AdminSearchConditionDto search = AdminSearchConditionDto.of(loginId, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.EXACT);
        List<ActivityKey> keys = adminActivityRepository.findKeys(ALL, search, null, null, 0, 50);

        assertThat(keys).extracting(ActivityKey::type).containsExactlyInAnyOrder(
                AdminActivityType.SIGNUP, AdminActivityType.CHARGE_REQUEST, AdminActivityType.SELF_BALANCE,
                AdminActivityType.ACCOUNT_STATUS);
        assertThat(keys).allMatch(key -> loginId.equals(key.loginId()) && key.occurredAt() != null);
        assertThat(adminActivityRepository.count(ALL, search, null, null)).isEqualTo(4);
        assertThat(adminActivityRepository.count(List.of(AdminActivityType.CHARGE_REQUEST, AdminActivityType.SELF_BALANCE,
                AdminActivityType.ADMIN_BALANCE), search, null, null)).isEqualTo(2);
    }

    @Test
    @DisplayName("기간 필터는 시작 시각 이상·끝 시각 미만만 남긴다")
    void periodFilter() {
        String loginId = "per" + System.nanoTime();
        userRepository.saveAndFlush(User.builder().loginId(loginId).name("기간테스터").role(Role.USER).isActive(true).build());
        AdminSearchConditionDto search = AdminSearchConditionDto.of(loginId, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.EXACT);
        LocalDateTime today = LocalDate.now().atStartOfDay();

        assertThat(adminActivityRepository.count(ALL, search, today, today.plusDays(1))).isEqualTo(1);
        assertThat(adminActivityRepository.count(ALL, search, today.plusDays(1), today.plusDays(2))).isZero();
    }
}
