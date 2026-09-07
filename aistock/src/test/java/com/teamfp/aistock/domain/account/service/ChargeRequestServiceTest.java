package com.teamfp.aistock.domain.account.service;

import java.time.LocalDate;
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

import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.dto.response.ChargeRequestResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
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

    private ChargeRequestService chargeRequestService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;

    private Account account;

    @BeforeEach
    void setUp() {
        chargeRequestService = new ChargeRequestService(chargeRequestRepository, accountService);

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
    @DisplayName("createRequest()는 정지된 계좌면 ACCOUNT_SUSPENDED 예외를 던지고 저장하지 않는다")
    void createRequest_accountSuspended() {
        account.suspend();
        when(accountService.getOwnedAccountForUpdate(USER_ID, ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> chargeRequestService.createRequest(
                USER_ID, ACCOUNT_ID, new ChargeRequestCreateRequest(10_000_000L, "사유")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);

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
