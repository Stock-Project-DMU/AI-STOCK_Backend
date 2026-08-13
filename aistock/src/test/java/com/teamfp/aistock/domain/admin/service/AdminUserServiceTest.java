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
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserStatusRequest;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserDetailResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminUserListResponse;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR 코드리뷰 반영 — Swagger 수동 확인만으로는 회귀를 못 잡아 단위 테스트를 추가한다.
 * 특히 유저 1명이 계좌를 여러 개(최대 3개) 가질 때 holdings/orders를 계좌별로 나눠 조회하지
 * 않고 배치(IN 절) 조회로 합치는 로직(AdminUserService.buildDetail())을 집중 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private HoldingValuationService holdingValuationService;

    private AdminUserService adminUserService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID_A = 10L;
    private static final Long ACCOUNT_ID_B = 20L;

    private User user;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(userRepository, accountRepository, orderRepository, holdingValuationService);

        user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(user, "userId", USER_ID);
    }

    private Account accountOf(Long accountId) {
        Account account = Account.builder()
                .user(user)
                .accountName("계좌" + accountId)
                .accountNumber("ACC-" + accountId)
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", accountId);
        return account;
    }

    private Order orderOf(Account account, String stockCode) {
        return Order.builder()
                .account(account)
                .stockCode(stockCode)
                .stockName(stockCode + "종목")
                .orderType(OrderType.BUY)
                .priceType(PriceType.MARKET)
                .orderPrice(50_000L)
                .quantity(1)
                .build();
    }

    @Test
    @DisplayName("getUsers()는 탈퇴 유저를 제외한 findAllByIsActiveTrue를 사용한다")
    void getUsers_excludesDeactivatedUsers() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(userRepository.findAllByIsActiveTrue(pageable)).thenReturn(page);

        Page<AdminUserListResponse> result = adminUserService.getUsers(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).userId()).isEqualTo(USER_ID);
        verify(userRepository).findAllByIsActiveTrue(pageable);
        verify(userRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("계좌가 여러 개면 계좌별로 나눠 조회하지 않고 accountId 리스트로 한 번에 배치 조회한다")
    void getUserDetail_multipleAccounts_batchesHoldingsAndOrders() {
        Account accountA = accountOf(ACCOUNT_ID_A);
        Account accountB = accountOf(ACCOUNT_ID_B);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(accountA, accountB));

        HoldingValuationDto holdingA = new HoldingValuationDto("005930", "삼성전자", 10, 50_000L, 60_000L);
        HoldingValuationDto holdingB = new HoldingValuationDto("000660", "SK하이닉스", 5, 100_000L, 110_000L);
        when(holdingValuationService.getHoldingValuations(List.of(ACCOUNT_ID_A, ACCOUNT_ID_B)))
                .thenReturn(List.of(holdingA, holdingB));

        Order orderA = orderOf(accountA, "005930");
        Order orderB = orderOf(accountB, "000660");
        when(orderRepository.findAllByAccountIdInOrderByOrderedAtDesc(List.of(ACCOUNT_ID_A, ACCOUNT_ID_B)))
                .thenReturn(List.of(orderB, orderA));

        AdminUserDetailResponse result = adminUserService.getUserDetail(USER_ID);

        assertThat(result.accounts()).hasSize(2);
        assertThat(result.holdings()).hasSize(2);
        assertThat(result.orders()).hasSize(2);
        // 배치 조회 쿼리가 이미 정렬해서 반환한 순서를 그대로 유지해야 한다(서비스가 재정렬하지 않음).
        assertThat(result.orders().get(0).stockCode()).isEqualTo("000660");
        assertThat(result.orders().get(1).stockCode()).isEqualTo("005930");

        // 계좌별 단건 조회(N+1)가 아니라 배치 조회 메서드가 정확히 1번만 호출됐는지 검증한다.
        verify(holdingValuationService, times(1)).getHoldingValuations(List.of(ACCOUNT_ID_A, ACCOUNT_ID_B));
        verify(holdingValuationService, never()).getHoldingValuations(anyLong());
        verify(orderRepository, times(1)).findAllByAccountIdInOrderByOrderedAtDesc(List.of(ACCOUNT_ID_A, ACCOUNT_ID_B));
        verify(orderRepository, never()).findAllByAccountIdOrderByOrderedAtDesc(anyLong());
    }

    @Test
    @DisplayName("계좌가 하나도 없으면 holdings/orders 조회 없이 빈 목록을 반환한다")
    void getUserDetail_noAccounts_returnsEmptyLists() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.getUserDetail(USER_ID);

        assertThat(result.accounts()).isEmpty();
        assertThat(result.holdings()).isEmpty();
        assertThat(result.orders()).isEmpty();
        verify(holdingValuationService, never()).getHoldingValuations(anyList());
        verify(orderRepository, never()).findAllByAccountIdInOrderByOrderedAtDesc(anyList());
    }

    @Test
    @DisplayName("존재하지 않는 userId면 USER_NOT_FOUND 예외를 던진다")
    void getUserDetail_userNotFound() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getUserDetail(USER_ID))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("탈퇴한 유저는 userId로 직접 조회해도 목록과 동일하게 USER_NOT_FOUND로 막힌다")
    void getUserDetail_deactivatedUser_blocked() {
        // findByUserIdAndIsActiveTrue는 isActive=false인 탈퇴 유저를 조회하지 못하므로 빈 값을 반환한다.
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getUserDetail(USER_ID))
                .isInstanceOf(CustomException.class);
        verify(accountRepository, never()).findAllByUserId(anyLong());
    }

    @Test
    @DisplayName("탈퇴한 유저는 상태 변경(updateUserStatus)도 USER_NOT_FOUND로 막힌다")
    void updateUserStatus_deactivatedUser_blocked() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.updateUserStatus(USER_ID, new AdminUserStatusRequest(UserStatus.ACTIVE)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("updateUserStatus()에 SUSPENDED를 보내면 유저 status가 SUSPENDED로 바뀐다")
    void updateUserStatus_suspend() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED));

        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("updateUserStatus()에 ACTIVE를 보내면 유저 status가 ACTIVE로 되돌아온다")
    void updateUserStatus_activate() {
        user.suspend();
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(USER_ID, new AdminUserStatusRequest(UserStatus.ACTIVE));

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
