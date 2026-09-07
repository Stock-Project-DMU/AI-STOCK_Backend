package com.teamfp.aistock.domain.admin.service;

import java.nio.charset.StandardCharsets;
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
import org.springframework.data.domain.Sort;
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
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private HoldingValuationService holdingValuationService;

    private AdminUserService adminUserService;

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long ACCOUNT_ID_A = 10L;
    private static final Long ACCOUNT_ID_B = 20L;

    private User user;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(userRepository, accountRepository, passwordEncoder, auditLogService, orderRepository, holdingValuationService);

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
    @DisplayName("getUsers()는 조건 없이 호출하면 searchUsers()에 query/status/role을 전부 null로 넘긴다")
    void getUsers_noFilter_passesAllNull() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(userRepository.searchUsers(null, null, null, pageable)).thenReturn(page);

        Page<AdminUserListResponse> result = adminUserService.getUsers(null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).userId()).isEqualTo(USER_ID);
        verify(userRepository).searchUsers(null, null, null, pageable);
    }

    @Test
    @DisplayName("getUsers()는 빈 문자열 query를 null로 정규화해서 searchUsers()에 넘긴다")
    void getUsers_blankQuery_normalizedToNull() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(userRepository.searchUsers(null, UserStatus.ACTIVE, null, pageable)).thenReturn(page);

        adminUserService.getUsers("   ", UserStatus.ACTIVE, null, pageable);

        verify(userRepository).searchUsers(null, UserStatus.ACTIVE, null, pageable);
    }

    @Test
    @DisplayName("getUsers()는 query/status/role을 그대로 searchUsers()에 전달한다")
    void getUsers_withFilters_delegatesToSearchUsers() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(userRepository.searchUsers("tester", UserStatus.ACTIVE, Role.USER, pageable)).thenReturn(page);

        Page<AdminUserListResponse> result = adminUserService.getUsers("tester", UserStatus.ACTIVE, Role.USER, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(userRepository).searchUsers("tester", UserStatus.ACTIVE, Role.USER, pageable);
    }

    @Test
    @DisplayName("계좌가 여러 개면 계좌별로 나눠 조회하지 않고 accountId 리스트로 한 번에 배치 조회한다")
    void getUserDetail_multipleAccounts_batchesHoldingsAndOrders() {
        Account accountA = accountOf(ACCOUNT_ID_A);
        Account accountB = accountOf(ACCOUNT_ID_B);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(accountA, accountB));

        HoldingValuationDto holdingA = new HoldingValuationDto(ACCOUNT_ID_A, "005930", "삼성전자", 10, 50_000L, 60_000L);
        HoldingValuationDto holdingB = new HoldingValuationDto(ACCOUNT_ID_B, "000660", "SK하이닉스", 5, 100_000L, 110_000L);
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
        // 계좌를 여러 개 합쳐서 보여주는 응답이라, 각 항목이 어느 계좌 소속인지 accountId로 구분할 수 있어야 한다.
        assertThat(result.holdings()).extracting("accountId").containsExactlyInAnyOrder(ACCOUNT_ID_A, ACCOUNT_ID_B);
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
    @DisplayName("getWithdrawnUsers()는 findAllByIsActiveFalse를 위임한다")
    void getWithdrawnUsers_delegatesToFindAllByIsActiveFalse() {
        User withdrawn = User.builder()
                .loginId("deleted_99")
                .name("탈퇴회원")
                .role(Role.USER)
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(withdrawn, "userId", 99L);
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(withdrawn), pageable, 1);
        when(userRepository.findAllByIsActiveFalse(pageable)).thenReturn(page);

        var result = adminUserService.getWithdrawnUsers(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).userId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("getAdminDetail()은 role이 ADMIN인 유저면 상세 정보를 반환한다")
    void getAdminDetail_admin_returnsDetail() {
        User admin = User.builder()
                .loginId("admin")
                .name("관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(admin, "userId", USER_ID);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(admin));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.getAdminDetail(USER_ID);

        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("getAdminDetail()은 role이 USER인 유저면 USER_NOT_FOUND 예외를 던진다")
    void getAdminDetail_regularUser_notFound() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> adminUserService.getAdminDetail(USER_ID))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(accountRepository, never()).findAllByUserId(anyLong());
    }

    @Test
    @DisplayName("updateAdminStatus()는 role이 USER인 유저를 대상으로 하면 USER_NOT_FOUND 예외를 던지고 상태를 바꾸지 않는다(코드리뷰 반영)")
    void updateAdminStatus_regularUser_blocked() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> adminUserService.updateAdminStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("updateAdminStatus()는 role이 ADMIN인 유저면 상태를 정상적으로 변경한다")
    void updateAdminStatus_admin_succeeds() {
        User targetAdmin = User.builder()
                .loginId("target-admin")
                .name("대상관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(targetAdmin, "userId", USER_ID);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(targetAdmin, targetAdmin)); // 활성 관리자가 2명 이상이라 정지 가능한 상황을 흉내낸다
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateAdminStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED));

        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
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

        assertThatThrownBy(() -> adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.ACTIVE)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("updateUserStatus()에 SUSPENDED를 보내면 유저 status가 SUSPENDED로 바뀐다")
    void updateUserStatus_suspend() {
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED));

        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("updateUserStatus()에 ACTIVE를 보내면 유저 status가 ACTIVE로 되돌아온다")
    void updateUserStatus_activate() {
        user.suspend();
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.ACTIVE));

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("관리자가 자기 자신을 정지시키려 하면 SELF_STATUS_CHANGE_NOT_ALLOWED 예외를 던진다")
    void updateUserStatus_self_blocked() {
        User admin = User.builder()
                .loginId("admin")
                .name("관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(admin, "userId", ADMIN_ID);
        when(userRepository.findByUserIdAndIsActiveTrue(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminUserService.updateUserStatus(ADMIN_ID, ADMIN_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SELF_STATUS_CHANGE_NOT_ALLOWED);

        verify(accountRepository, never()).findAllByUserId(anyLong());
    }

    @Test
    @DisplayName("활성 상태인 마지막 ADMIN을 정지시키려 하면 LAST_ADMIN_SUSPEND_NOT_ALLOWED 예외를 던진다")
    void updateUserStatus_lastActiveAdmin_blocked() {
        User targetAdmin = User.builder()
                .loginId("target-admin")
                .name("대상관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(targetAdmin, "userId", USER_ID);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.LAST_ADMIN_SUSPEND_NOT_ALLOWED);

        verify(accountRepository, never()).findAllByUserId(anyLong());
    }

    @Test
    @DisplayName("정지된 ADMIN이 다른 활성 ADMIN 곁에 있으면 정지시킬 수 있다")
    void updateUserStatus_adminWithOtherActiveAdmins_allowed() {
        User targetAdmin = User.builder()
                .loginId("target-admin")
                .name("대상관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(targetAdmin, "userId", USER_ID);
        User otherAdmin = User.builder()
                .loginId("other-admin")
                .name("다른관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(otherAdmin, "userId", 3L);
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findAllByRoleAndStatusAndIsActiveTrueForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(targetAdmin, otherAdmin));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED));

        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("이미 정지된 유저에게 다시 SUSPENDED를 보내면 lockout 가드 없이 그대로 통과한다(멱등)")
    void updateUserStatus_alreadySuspended_isIdempotent() {
        User targetAdmin = User.builder()
                .loginId("target-admin")
                .name("대상관리자")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(targetAdmin, "userId", USER_ID);
        targetAdmin.suspend();
        when(userRepository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(targetAdmin));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        AdminUserDetailResponse result = adminUserService.updateUserStatus(ADMIN_ID, USER_ID, new AdminUserStatusRequest(UserStatus.SUSPENDED));

        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        // 이미 SUSPENDED인 경우 lockout 검증(마지막 admin 확인 락 조회)까지 갈 필요가 없다.
        verify(userRepository, never()).findAllByRoleAndStatusAndIsActiveTrueForUpdate(any(Role.class), any(UserStatus.class));
    }

    @Test
    @DisplayName("exportUsersCsv()는 UTF-8 BOM으로 시작하고 헤더·회원 정보를 CSV로 담는다")
    void exportUsersCsv_returnsCsvWithBomAndUserRows() {
        // createdAt은 @CreatedDate(JPA Auditing)라 순수 빌더로는 채워지지 않으므로,
        // exportUsersCsv()가 getCreatedAt().toString()을 호출할 때 NPE가 나지 않도록 직접 채운다.
        ReflectionTestUtils.setField(user, "createdAt", java.time.LocalDateTime.of(2026, 8, 1, 12, 0));
        Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> page = new PageImpl<>(List.of(user), pageable, 1);
        when(userRepository.searchUsers(null, null, null, pageable)).thenReturn(page);

        byte[] csv = adminUserService.exportUsersCsv(null, null, null, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        String content = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(content).contains("회원번호,아이디,이름,이메일,역할,상태,가입일");
        assertThat(content).contains("tester");
        assertThat(content).contains("테스터");
    }

    @Test
    @DisplayName("createAdmin()은 중복 아이디/이메일이 없으면 role=ADMIN으로 저장한다")
    void createAdmin_success() {
        when(userRepository.existsByLoginId("admin02")).thenReturn(false);
        when(userRepository.existsByEmail("admin02@example.com")).thenReturn(false);
        when(passwordEncoder.encode("temporary-password1")).thenReturn("encoded-password");

        com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest request =
                new com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest(
                        "admin02", "temporary-password1", "운영 관리자", "admin02@example.com");

        AdminUserListResponse result = adminUserService.createAdmin(ADMIN_ID, request);

        assertThat(result.loginId()).isEqualTo("admin02");
        assertThat(result.role()).isEqualTo(Role.ADMIN);
        verify(userRepository).save(argThat(u -> u.getRole() == Role.ADMIN
                && u.getLoginId().equals("admin02")
                && u.getPassword().equals("encoded-password")));
    }

    @Test
    @DisplayName("createAdmin()은 아이디가 이미 존재하면 DUPLICATE_LOGIN_ID 예외를 던진다")
    void createAdmin_duplicateLoginId() {
        when(userRepository.existsByLoginId("admin02")).thenReturn(true);

        com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest request =
                new com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest(
                        "admin02", "temporary-password1", "운영 관리자", "admin02@example.com");

        assertThatThrownBy(() -> adminUserService.createAdmin(ADMIN_ID, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("createAdmin()은 이메일이 이미 존재하면 DUPLICATE_EMAIL 예외를 던진다")
    void createAdmin_duplicateEmail() {
        when(userRepository.existsByLoginId("admin02")).thenReturn(false);
        when(userRepository.existsByEmail("admin02@example.com")).thenReturn(true);

        com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest request =
                new com.teamfp.aistock.domain.admin.dto.request.AdminCreateRequest(
                        "admin02", "temporary-password1", "운영 관리자", "admin02@example.com");

        assertThatThrownBy(() -> adminUserService.createAdmin(ADMIN_ID, request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verify(userRepository, never()).save(any(User.class));
    }
}
