package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.service.AccountTransactionService;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountAdjustmentRequest;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminAccountDetailResponse;
import com.teamfp.aistock.domain.order.service.OrderService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private AccountTransactionService accountTransactionService;

    @Mock
    private AuditLogService auditLogService;

    private AdminAccountService adminAccountService;

    private static final Long ACCOUNT_ID = 10L;
    private static final Long ADMIN_ID = 999L;

    private Account account;

    @BeforeEach
    void setUp() {
        adminAccountService = new AdminAccountService(accountRepository, orderService, accountTransactionService, auditLogService);

        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(user, "userId", 1L);

        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("ACC-10")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", ACCOUNT_ID);
    }

    @Test
    @DisplayName("getAccountDetail()은 계좌 정보와 소유자 이름을 함께 반환한다")
    void getAccountDetail_returnsDetail() {
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AdminAccountDetailResponse result = adminAccountService.getAccountDetail(ACCOUNT_ID);

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.userName()).isEqualTo("테스터");
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 accountId면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void getAccountDetail_notFound() {
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAccountService.getAccountDetail(ACCOUNT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("updateAccountStatus()에 SUSPENDED를 보내면 계좌 status가 SUSPENDED로 바뀌고 기존 PENDING 주문을 함께 취소한다")
    void updateAccountStatus_suspend() {
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AdminAccountDetailResponse result = adminAccountService.updateAccountStatus(
                ADMIN_ID,
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.SUSPENDED, "사유"));

        assertThat(result.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(orderService).cancelAllPendingOrdersForSuspension(account);
    }

    @Test
    @DisplayName("updateAccountStatus()에 ACTIVE를 보내면 계좌 status가 ACTIVE로 되돌아오고 주문 취소는 호출하지 않는다")
    void updateAccountStatus_activate() {
        account.suspend();
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AdminAccountDetailResponse result = adminAccountService.updateAccountStatus(
                ADMIN_ID,
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.ACTIVE, "사유"));

        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(orderService, never()).cancelAllPendingOrdersForSuspension(account);
    }

    @Test
    @DisplayName("getAccounts()는 조건 없이 호출하면 searchAccountsWithUser()에 query/status를 전부 null로 넘긴다")
    void getAccounts_noFilter_passesAllNull() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Account> page = new PageImpl<>(List.of(account), pageable, 1);
        when(accountRepository.searchAccountsWithUser(null, null, pageable)).thenReturn(page);

        Page<AdminAccountDetailResponse> result = adminAccountService.getAccounts(null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).accountId()).isEqualTo(ACCOUNT_ID);
        verify(accountRepository).searchAccountsWithUser(null, null, pageable);
    }

    @Test
    @DisplayName("getAccounts()는 빈 문자열 query를 null로 정규화해서 searchAccountsWithUser()에 넘긴다")
    void getAccounts_blankQuery_normalizedToNull() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Account> page = new PageImpl<>(List.of(account), pageable, 1);
        when(accountRepository.searchAccountsWithUser(null, AccountStatus.SUSPENDED, pageable)).thenReturn(page);

        adminAccountService.getAccounts("   ", AccountStatus.SUSPENDED, pageable);

        verify(accountRepository).searchAccountsWithUser(null, AccountStatus.SUSPENDED, pageable);
    }

    @Test
    @DisplayName("adjustBalance()에 ADMIN_CHARGE를 보내면 잔고가 오르고 원장에 기록한다")
    void adjustBalance_charge_increasesBalance() {
        when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AdminAccountDetailResponse result = adminAccountService.adjustBalance(
                ADMIN_ID, ACCOUNT_ID, new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_CHARGE, 500_000L, "이벤트 보상 지급"));

        assertThat(result.balance()).isEqualTo(1_500_000L);
        verify(accountTransactionService).record(account, AccountTransactionType.ADMIN_CHARGE, 500_000L, 1_000_000L,
                null, null, ADMIN_ID, "이벤트 보상 지급");
    }

    @Test
    @DisplayName("adjustBalance()에 ADMIN_DEDUCTION을 보내면 잔고가 줄고 원장에 기록한다")
    void adjustBalance_deduction_decreasesBalance() {
        when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AdminAccountDetailResponse result = adminAccountService.adjustBalance(
                ADMIN_ID, ACCOUNT_ID, new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_DEDUCTION, 300_000L, "이상 거래 회수"));

        assertThat(result.balance()).isEqualTo(700_000L);
        verify(accountTransactionService).record(account, AccountTransactionType.ADMIN_DEDUCTION, -300_000L, 1_000_000L,
                null, null, ADMIN_ID, "이상 거래 회수");
    }

    @Test
    @DisplayName("adjustBalance()의 ADMIN_DEDUCTION 금액이 잔고보다 크면 INSUFFICIENT_BALANCE 예외를 던진다")
    void adjustBalance_deduction_insufficientBalance() {
        when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> adminAccountService.adjustBalance(
                ADMIN_ID, ACCOUNT_ID, new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_DEDUCTION, 2_000_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        verify(accountTransactionService, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyString());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("adjustBalance()는 balance는 충분해도 baseBalance보다 큰 금액을 차감하려 하면 INSUFFICIENT_BALANCE 예외를 던진다(baseBalance 음수화 방지)")
    void adjustBalance_deduction_baseBalanceWouldGoNegative() {
        // 거래 수익으로 balance(1,500,000)가 baseBalance(1,000,000)보다 큰 상태를 재현한다.
        ReflectionTestUtils.setField(account, "balance", 1_500_000L);
        when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        // balance(1,500,000) 기준으로는 통과하지만 baseBalance(1,000,000)보다 큰 1,200,000을 요청.
        assertThatThrownBy(() -> adminAccountService.adjustBalance(
                ADMIN_ID, ACCOUNT_ID, new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_DEDUCTION, 1_200_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(account.getBaseBalance()).isEqualTo(1_000_000L); // 변경되지 않음
        verify(accountTransactionService, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("adjustBalance()는 조정 후 감사 로그를 남긴다")
    void adjustBalance_recordsAuditLog() {
        when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        adminAccountService.adjustBalance(
                ADMIN_ID, ACCOUNT_ID, new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_CHARGE, 500_000L, "이벤트 보상 지급"));

        verify(auditLogService).record(ADMIN_ID, AuditLogService.ACTION_ACCOUNT_ADJUSTMENT, AuditLogService.TARGET_ACCOUNT,
                ACCOUNT_ID, "1000000", "1500000", "이벤트 보상 지급");
    }

    @Test
    @DisplayName("존재하지 않는 accountId로 상태 변경을 시도하면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void updateAccountStatus_notFound() {
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAccountService.updateAccountStatus(
                ADMIN_ID,
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.SUSPENDED, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
