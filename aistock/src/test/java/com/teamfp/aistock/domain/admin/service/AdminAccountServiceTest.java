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
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OrderService orderService;

    private AdminAccountService adminAccountService;

    private static final Long ACCOUNT_ID = 10L;

    private Account account;

    @BeforeEach
    void setUp() {
        adminAccountService = new AdminAccountService(accountRepository, orderService);

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
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.SUSPENDED));

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
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.ACTIVE));

        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(orderService, never()).cancelAllPendingOrdersForSuspension(account);
    }

    @Test
    @DisplayName("존재하지 않는 accountId로 상태 변경을 시도하면 ACCOUNT_NOT_FOUND 예외를 던진다")
    void updateAccountStatus_notFound() {
        when(accountRepository.findAccountWithUserById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAccountService.updateAccountStatus(
                ACCOUNT_ID, new AdminAccountStatusRequest(AccountStatus.SUSPENDED)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
