package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountTransactionSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountTransactionResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeHistoryEntryType;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 충전·차감 이력(feat/admin-improvements)을 실제 MySQL로 검증한다 — 잔고 내역 + 거절된 충전 요청 합치기,
 * 유형 필터, 승인 입금에 원래 요청 붙이기, 처리 관리자 아이디, 금액 정렬. 사전 조건: 로컬 MySQL이 떠 있어야 한다.
 * @Transactional로 넣은 행은 롤백된다. 로컬 DB의 기존 기록과 섞이지 않도록 이 테스트가 만든 회원 아이디로 정확히
 * 일치 검색해서 검증한다.
 */
@SpringBootTest
@Transactional
class AdminAccountTransactionServiceIntegrationTest {

    @Autowired
    private AdminAccountTransactionService adminAccountTransactionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountTransactionRepository accountTransactionRepository;
    @Autowired
    private ChargeRequestRepository chargeRequestRepository;

    @Test
    @DisplayName("충전·차감 4종과 거절된 요청이 함께 나오고(주문·이자·대기 요청 제외), 승인 입금에는 원래 요청이 붙는다")
    void mergesTransactionsAndRejectedRequests() {
        long suffix = System.nanoTime();
        String loginId = "txhist" + suffix;
        User admin = saveUser("txadmin" + suffix, Role.ADMIN);
        Account account = saveAccount(saveUser(loginId, Role.USER), suffix);
        ChargeRequest approved = chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(2_000L).reason("승인될 요청").build());
        approved.approve(admin, "승인");
        ChargeRequest rejected = chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(9_000L).reason("거절될 요청").build());
        rejected.reject(admin, "사유 부족");
        chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(1L).reason("대기 중").build());
        save(account, AccountTransactionType.AUTO_CHARGE, 1_000L, null, null);
        save(account, AccountTransactionType.ADMIN_CHARGE, 2_000L, admin.getUserId(), approved.getRequestId());
        save(account, AccountTransactionType.ADMIN_DEDUCTION, -500L, admin.getUserId(), null);
        save(account, AccountTransactionType.ORDER_BUY, -700L, null, null);
        save(account, AccountTransactionType.INTEREST, 10L, null, null);
        chargeRequestRepository.flush();

        Page<AdminAccountTransactionResponse> all = history(null, loginId, AdminSortType.AMOUNT_DESC);

        assertThat(all.getTotalElements()).isEqualTo(4);
        // 금액 큰순: 거절 요청 9,000 → 승인 입금 2,000 → 셀프 충전 1,000 → 차감 500(절댓값)
        assertThat(all.getContent()).extracting(AdminAccountTransactionResponse::entryType).containsExactly(
                AdminChargeHistoryEntryType.REJECTED_REQUEST, AdminChargeHistoryEntryType.TRANSACTION,
                AdminChargeHistoryEntryType.TRANSACTION, AdminChargeHistoryEntryType.TRANSACTION);
        AdminAccountTransactionResponse rejectedRow = all.getContent().get(0);
        assertThat(rejectedRow.amount()).isNull();
        assertThat(rejectedRow.chargeRequest().status()).isEqualTo(ChargeRequestStatus.REJECTED);
        assertThat(rejectedRow.reason()).isEqualTo("사유 부족");
        AdminAccountTransactionResponse approvedRow = all.getContent().get(1);
        assertThat(approvedRow.type()).isEqualTo(AccountTransactionType.ADMIN_CHARGE);
        assertThat(approvedRow.processedByLoginId()).isEqualTo("txadmin" + suffix);
        assertThat(approvedRow.chargeRequest().requestId()).isEqualTo(approved.getRequestId());
        assertThat(approvedRow.chargeRequest().reason()).isEqualTo("승인될 요청");

        assertThat(history("REJECTED", loginId, AdminSortType.LATEST).getContent()).singleElement()
                .satisfies(row -> assertThat(row.entryType()).isEqualTo(AdminChargeHistoryEntryType.REJECTED_REQUEST));
        assertThat(history("ADMIN_DEDUCTION", loginId, AdminSortType.LATEST).getContent()).singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualTo(-500L));
    }

    @Test
    @DisplayName("충전·차감이 아닌 유형으로 거르면 INVALID_INPUT")
    void rejectsNonChargeType() {
        assertThatThrownBy(() -> history("ORDER_BUY", null, AdminSortType.LATEST))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private Page<AdminAccountTransactionResponse> history(String type, String loginId, AdminSortType sortType) {
        return adminAccountTransactionService.getChargeDeductionHistory(type,
                AdminSearchConditionDto.of(loginId, AdminAccountTransactionSearchField.LOGIN_ID, AdminSearchMatchType.EXACT),
                sortType, null, null, PageRequest.of(0, 20));
    }

    private User saveUser(String loginId, Role role) {
        return userRepository.save(User.builder().loginId(loginId).name("이력테스터").role(role).isActive(true).build());
    }

    private Account saveAccount(User user, long suffix) {
        return accountRepository.save(Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("H" + (suffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
    }

    private void save(Account account, AccountTransactionType type, long amount, Long processedBy, Long chargeRequestId) {
        accountTransactionRepository.save(AccountTransaction.builder()
                .account(account).type(type).amount(amount)
                .balanceBefore(1_000_000L).balanceAfter(1_000_000L + amount)
                .relatedChargeRequestId(chargeRequestId)
                .processedBy(processedBy).reason("테스트").build());
    }
}
