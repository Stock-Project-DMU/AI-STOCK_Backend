package com.teamfp.aistock.domain.account.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.teamfp.aistock.domain.account.dto.ChargeSource;
import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.dto.response.ChargeHistoryResponse;
import com.teamfp.aistock.domain.account.dto.response.ChargeRequestResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargeRequestServiceTest {

    @Mock
    private ChargeRequestRepository chargeRequestRepository;

    @Mock
    private AccountService accountService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AccountTransactionService accountTransactionService;

    private ChargeRequestService chargeRequestService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;

    private Account account;

    @BeforeEach
    void setUp() {
        chargeRequestService = new ChargeRequestService(chargeRequestRepository, accountService, notificationService, accountTransactionService);

        User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        ReflectionTestUtils.setField(user, "userId", USER_ID);

        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("ACC-10")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", ACCOUNT_ID);
        // 관리자 충전 요청은 직접 충전 3회를 모두 쓴 계좌만 할 수 있으므로 기본 픽스처는 한도 소진 상태로 둔다.
        ReflectionTestUtils.setField(account, "chargeCount", Account.MAX_CHARGE_COUNT);
    }

    @Test
    @DisplayName("createRequest()는 직접 충전 횟수가 남아 있으면 CHARGE_REQUEST_NOT_ALLOWED 예외를 던지고 저장하지 않는다")
    void createRequest_chargeCountRemaining() {
        ReflectionTestUtils.setField(account, "chargeCount", Account.MAX_CHARGE_COUNT - 1);
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARGE_REQUEST_NOT_ALLOWED);
        verify(chargeRequestRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(ChargeRequest.class));
    }

    private AccountTransaction chargeTransaction(AccountTransactionType type, long amount, long balanceAfter,
            Long relatedChargeRequestId, String reason, LocalDateTime createdAt) {
        AccountTransaction transaction = AccountTransaction.builder()
                .account(account).type(type).amount(amount).balanceBefore(balanceAfter - amount).balanceAfter(balanceAfter)
                .relatedChargeRequestId(relatedChargeRequestId).reason(reason).build();
        ReflectionTestUtils.setField(transaction, "createdAt", createdAt);
        return transaction;
    }

    @Test
    @DisplayName("getChargeHistory()는 직접 충전(SELF)·관리자 충전 요청·관리자 지급(ADMIN)을 합쳐 최신순으로 돌려주고, 승인된 요청의 원장은 중복 나열하지 않는다")
    void getChargeHistory_mergesSelfAndAdminCharges() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 9, 0);
        User admin = User.builder().loginId("admin").name("관리자").role(Role.ADMIN).isActive(true).build();

        ChargeRequest approvedRequest = ChargeRequest.builder().account(account).amount(5_000_000L).reason("추가 학습").build();
        ReflectionTestUtils.setField(approvedRequest, "requestId", 7L);
        ReflectionTestUtils.setField(approvedRequest, "requestedAt", base.plusHours(2));
        approvedRequest.approve(admin, "승인합니다");
        ChargeRequest pendingRequest = ChargeRequest.builder().account(account).amount(3_000_000L).reason("추가 요청").build();
        ReflectionTestUtils.setField(pendingRequest, "requestId", 8L);
        ReflectionTestUtils.setField(pendingRequest, "requestedAt", base.plusHours(4));

        when(accountTransactionService.getTransactionsByTypes(ACCOUNT_ID,
                List.of(AccountTransactionType.AUTO_CHARGE, AccountTransactionType.ADMIN_CHARGE))).thenReturn(List.of(
                chargeTransaction(AccountTransactionType.AUTO_CHARGE, 1_000_000L, 2_000_000L, null, "직접 충전", base),
                chargeTransaction(AccountTransactionType.ADMIN_CHARGE, 5_000_000L, 7_000_000L, 7L, "승인합니다", base.plusHours(3)),
                chargeTransaction(AccountTransactionType.ADMIN_CHARGE, 2_000_000L, 9_000_000L, null, "이벤트 지급", base.plusHours(5))));
        when(chargeRequestRepository.findAllByAccount_AccountId(ACCOUNT_ID)).thenReturn(List.of(approvedRequest, pendingRequest));

        List<ChargeHistoryResponse> history = chargeRequestService.getChargeHistory(USER_ID, ACCOUNT_ID);

        assertThat(history).hasSize(4);
        // 최신순: 관리자 지급(5시) → 대기 요청(4시) → 승인 요청(2시) → 직접 충전(0시)
        assertThat(history).extracting(ChargeHistoryResponse::source)
                .containsExactly(ChargeSource.ADMIN, ChargeSource.ADMIN, ChargeSource.ADMIN, ChargeSource.SELF);
        assertThat(history).extracting(ChargeHistoryResponse::status).containsExactly(
                ChargeRequestStatus.APPROVED, ChargeRequestStatus.PENDING, ChargeRequestStatus.APPROVED, ChargeRequestStatus.APPROVED);
        assertThat(history.get(1).balanceAfter()).isNull();          // 대기 요청은 아직 반영 전
        assertThat(history.get(2).balanceAfter()).isEqualTo(7_000_000L); // 승인 요청은 승인 원장의 잔고
        assertThat(history.get(2).requestId()).isEqualTo(7L);
        assertThat(history.get(3).amount()).isEqualTo(1_000_000L);
        verify(accountService).getOwnedAccount(USER_ID, ACCOUNT_ID);
    }

    @Test
    @DisplayName("createRequest()는 승인돼도 예치금이 1조원을 넘을 요청이면 DEPOSIT_LIMIT_EXCEEDED 예외를 던지고 저장하지 않는다")
    void createRequest_depositLimitExceeded() {
        ReflectionTestUtils.setField(account, "balance", com.teamfp.aistock.domain.account.entity.Account.MAX_DEPOSIT_AMOUNT);
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(1L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
        verify(chargeRequestRepository, never()).save(org.mockito.ArgumentMatchers.any(ChargeRequest.class));
    }

    @Test
    @DisplayName("createRequest()는 PENDING 요청이 없으면 새 요청을 저장한다")
    void createRequest_success() {
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);
        when(chargeRequestRepository.existsByAccount_AccountIdAndStatus(ACCOUNT_ID, ChargeRequestStatus.PENDING)).thenReturn(false);

        ChargeRequestResponse result = chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "투자 학습을 위한 추가 요청"));

        assertThat(result.amount()).isEqualTo(10_000_000L);
        assertThat(result.status()).isEqualTo(ChargeRequestStatus.PENDING);
        verify(chargeRequestRepository).save(org.mockito.ArgumentMatchers.any(ChargeRequest.class));
        verify(notificationService).notify(USER_ID, NotificationType.ACCOUNT, "충전 요청 접수",
                "10,000,000원 충전 요청이 접수되었습니다. 관리자의 결정을 기다려 주세요.");
    }

    @Test
    @DisplayName("createRequest()는 이미 PENDING 요청이 있으면 CHARGE_REQUEST_ALREADY_PENDING 예외를 던진다")
    void createRequest_alreadyPending() {
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);
        when(chargeRequestRepository.existsByAccount_AccountIdAndStatus(ACCOUNT_ID, ChargeRequestStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARGE_REQUEST_ALREADY_PENDING);

        verify(chargeRequestRepository, never()).save(org.mockito.ArgumentMatchers.any(ChargeRequest.class));
    }

    @Test
    @DisplayName("createRequest()는 정지된 계좌면 ACCOUNT_SUSPENDED_CHARGE 예외를 던지고 저장하지 않는다")
    void createRequest_accountSuspended() {
        account.suspend();
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED_CHARGE);

        verify(chargeRequestRepository, never()).existsByAccount_AccountIdAndStatus(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(chargeRequestRepository, never()).save(org.mockito.ArgumentMatchers.any(ChargeRequest.class));
    }

    @Test
    @DisplayName("createRequest()는 계좌를 비관적 락으로 조회한다(동시 요청의 중복 PENDING 생성 방지)")
    void createRequest_locksAccountRow() {
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);
        when(chargeRequestRepository.existsByAccount_AccountIdAndStatus(ACCOUNT_ID, ChargeRequestStatus.PENDING)).thenReturn(false);

        chargeRequestService.createRequest(USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "사유"));

        verify(accountService).getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID);
        verify(accountService, never()).getOwnedAccount(USER_ID, ACCOUNT_ID);
    }

    @Test
    @DisplayName("getMyRequestDetail()은 존재하지 않는 requestId면 CHARGE_REQUEST_NOT_FOUND 예외를 던진다")
    void getMyRequestDetail_notFound() {
        when(accountService.getOwnedAccount(USER_ID, ACCOUNT_ID)).thenReturn(account);
        when(chargeRequestRepository.findByRequestIdAndAccountId(99L, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chargeRequestService.getMyRequestDetail(USER_ID, ACCOUNT_ID, 99L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARGE_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("getMyRequests()는 계좌 소유권을 검증한 뒤 목록을 반환한다")
    void getMyRequests_success() {
        Pageable pageable = PageRequest.of(0, 20);
        ChargeRequest chargeRequest = ChargeRequest.builder().account(account).amount(1_000_000L).reason("사유").build();
        Page<ChargeRequest> page = new PageImpl<>(java.util.List.of(chargeRequest), pageable, 1);
        when(accountService.getOwnedAccount(USER_ID, ACCOUNT_ID)).thenReturn(account);
        when(chargeRequestRepository.findAllByAccountId(ACCOUNT_ID, pageable)).thenReturn(page);

        Page<ChargeRequestResponse> result = chargeRequestService.getMyRequests(USER_ID, ACCOUNT_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
    }
}
