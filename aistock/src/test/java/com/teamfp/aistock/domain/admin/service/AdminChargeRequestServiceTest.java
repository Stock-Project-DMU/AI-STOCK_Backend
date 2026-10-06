package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeDecisionRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeRequestDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeRequestResponse;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.notification.service.NotificationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminChargeRequestServiceTest {

    @Mock
    private ChargeRequestRepository chargeRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.teamfp.aistock.domain.account.service.AccountTransactionService accountTransactionService;

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;

    private AdminChargeRequestService adminChargeRequestService;

    private static final Long ADMIN_ID = 999L;
    private static final Long REQUEST_ID = 1L;

    private Account account;
    private User admin;

    @BeforeEach
    void setUp() {
        adminChargeRequestService = new AdminChargeRequestService(chargeRequestRepository, userRepository, accountTransactionService, auditLogService, notificationService);

        User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        ReflectionTestUtils.setField(user, "userId", 1L);
        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("ACC-10")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", 10L);

        admin = User.builder().loginId("admin").name("관리자").role(Role.ADMIN).isActive(true).build();
        ReflectionTestUtils.setField(admin, "userId", ADMIN_ID);
    }

    private ChargeRequest chargeRequestOf() {
        ChargeRequest chargeRequest = ChargeRequest.builder().account(account).amount(10_000_000L).reason("추가 충전 요청").build();
        ReflectionTestUtils.setField(chargeRequest, "requestId", REQUEST_ID);
        return chargeRequest;
    }

    @Test
    @DisplayName("충전 요청 상세에는 계좌 잔고·한도까지 남은 금액·셀프 충전 횟수와 같은 계좌의 다른 요청 이력이 담긴다")
    void getRequestDetail_containsAccountSnapshotAndHistory() {
        ChargeRequest chargeRequest = chargeRequestOf();
        ChargeRequest older = ChargeRequest.builder().account(account).amount(5_000_000L).reason("예전 요청").build();
        ReflectionTestUtils.setField(older, "requestId", REQUEST_ID + 1);
        ReflectionTestUtils.setField(account, "chargeCount", 3);
        when(chargeRequestRepository.findWithAccountAndUserById(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));
        when(chargeRequestRepository.findAllByAccountId(10L,
                org.springframework.data.domain.PageRequest.of(0, AdminChargeRequestDetailResponse.RECENT_REQUEST_LIMIT + 1)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(chargeRequest, older)));

        AdminChargeRequestDetailResponse result = adminChargeRequestService.getRequestDetail(REQUEST_ID);

        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.accountBalance()).isEqualTo(1_000_000L);
        assertThat(result.depositRemaining())
                .isEqualTo(com.teamfp.aistock.domain.account.entity.Account.MAX_DEPOSIT_AMOUNT - 1_000_000L);
        assertThat(result.chargeCount()).isEqualTo(3);
        // 이 요청 자신은 이력에서 빠지고 다른 요청만 남는다.
        assertThat(result.recentRequests()).extracting(AdminChargeRequestResponse::requestId).containsExactly(REQUEST_ID + 1);
    }

    @Test
    @DisplayName("decide()로 승인할 때 예치금이 1조원을 넘게 되면 DEPOSIT_LIMIT_EXCEEDED 예외를 던지고 잔고를 바꾸지 않는다")
    void decide_approve_depositLimitExceeded() {
        ChargeRequest chargeRequest = chargeRequestOf();
        ReflectionTestUtils.setField(account, "balance", com.teamfp.aistock.domain.account.entity.Account.MAX_DEPOSIT_AMOUNT);
        when(chargeRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));
        when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);

        assertThatThrownBy(() -> adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.APPROVED, "승인합니다")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
        assertThat(account.getBalance()).isEqualTo(com.teamfp.aistock.domain.account.entity.Account.MAX_DEPOSIT_AMOUNT);
        assertThat(chargeRequest.getStatus()).isEqualTo(ChargeRequestStatus.PENDING);
    }

    @Test
    @DisplayName("decide()에 APPROVED를 보내면 계좌 잔고가 오르고 상태가 APPROVED로 바뀐다")
    void decide_approve_increasesBalance() {
        ChargeRequest chargeRequest = chargeRequestOf();
        when(chargeRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));
        when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(chargeRequestRepository.findWithAccountAndUserById(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));

        AdminChargeRequestResponse result = adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.APPROVED, "승인합니다"));

        assertThat(result.status()).isEqualTo(ChargeRequestStatus.APPROVED);
        assertThat(account.getBalance()).isEqualTo(11_000_000L);
        assertThat(account.getBaseBalance()).isEqualTo(11_000_000L);
        assertThat(chargeRequest.getDecidedBy()).isEqualTo(admin);
        verify(notificationService).notify(1L, NotificationType.ACCOUNT, "충전 요청 승인",
                "10,000,000원 충전 요청이 승인되어 계좌에 반영되었습니다. 사유: 승인합니다");
    }

    @Test
    @DisplayName("decide()에 REJECTED를 보내면 계좌 잔고는 그대로고 상태만 REJECTED로 바뀐다")
    void decide_reject_doesNotChangeBalance() {
        ChargeRequest chargeRequest = chargeRequestOf();
        when(chargeRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));
        when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(chargeRequestRepository.findWithAccountAndUserById(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));

        AdminChargeRequestResponse result = adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.REJECTED, "한도 초과"));

        assertThat(result.status()).isEqualTo(ChargeRequestStatus.REJECTED);
        assertThat(account.getBalance()).isEqualTo(1_000_000L);
        verify(notificationService).notify(1L, NotificationType.ACCOUNT, "충전 요청 거절",
                "10,000,000원 충전 요청이 거절되었습니다. 사유: 한도 초과");
    }

    @Test
    @DisplayName("이미 처리된 요청을 다시 처리하려 하면 CHARGE_REQUEST_ALREADY_PROCESSED 예외를 던진다")
    void decide_alreadyProcessed() {
        ChargeRequest chargeRequest = chargeRequestOf();
        chargeRequest.approve(admin, "이미 승인됨");
        when(chargeRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(chargeRequest));

        assertThatThrownBy(() -> adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.APPROVED, "재승인")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARGE_REQUEST_ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("decision에 PENDING을 보내면 INVALID_INPUT 예외를 던진다")
    void decide_invalidPendingDecision() {
        assertThatThrownBy(() -> adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.PENDING, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("존재하지 않는 requestId면 CHARGE_REQUEST_NOT_FOUND 예외를 던진다")
    void decide_notFound() {
        when(chargeRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminChargeRequestService.decide(
                ADMIN_ID, REQUEST_ID, new AdminChargeDecisionRequest(ChargeRequestStatus.APPROVED, "승인")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHARGE_REQUEST_NOT_FOUND);
    }
}
