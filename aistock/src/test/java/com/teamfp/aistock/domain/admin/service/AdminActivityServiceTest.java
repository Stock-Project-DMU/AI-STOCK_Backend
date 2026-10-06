package com.teamfp.aistock.domain.admin.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityCategory;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityResponse;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityType;
import com.teamfp.aistock.domain.admin.entity.AuditLog;
import com.teamfp.aistock.domain.admin.repository.AdminActivityRepository;
import com.teamfp.aistock.domain.admin.repository.AdminActivityRepository.ActivityKey;
import com.teamfp.aistock.domain.admin.repository.AuditLogRepository;
import com.teamfp.aistock.domain.inquiry.repository.InquiryRepository;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderStatus;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminActivityServiceTest {

    @Mock
    private AdminActivityRepository adminActivityRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ChargeRequestRepository chargeRequestRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private AccountTransactionRepository accountTransactionRepository;
    @Mock
    private InquiryRepository inquiryRepository;

    private AdminActivityService adminActivityService;

    private static final AdminSearchConditionDto NO_SEARCH = AdminSearchConditionDto.of(null, null, null);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 9, 0);

    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        adminActivityService = new AdminActivityService(adminActivityRepository, userRepository, accountRepository,
                orderRepository, chargeRequestRepository, auditLogRepository, accountTransactionRepository, inquiryRepository);
        user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        ReflectionTestUtils.setField(user, "userId", 1L);
        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("110000000001")
                .openedAt(LocalDate.now())
                .baseBalance(10_000_000L)
                .balance(10_000_000L)
                .build();
        ReflectionTestUtils.setField(account, "accountId", 10L);
    }

    private ActivityKey key(AdminActivityType type, long id) {
        return new ActivityKey(type, id, AT, 1L, "tester", "테스터");
    }

    @Test
    @DisplayName("충전 요청은 요청·승인·입금(금액·처리 후 잔고)이 한 줄에 담기고, 탭(category)은 충전차감이력이다")
    void chargeRequestMergedIntoOneRow() {
        ChargeRequest request = ChargeRequest.builder().account(account).amount(5_000_000L).reason("추가 충전").build();
        ReflectionTestUtils.setField(request, "requestId", 20L);
        User admin = User.builder().loginId("admin").name("관리자").role(Role.ADMIN).isActive(true).build();
        request.approve(admin, "승인합니다");
        AccountTransaction deposit = AccountTransaction.builder().account(account).type(AccountTransactionType.ADMIN_CHARGE)
                .amount(5_000_000L).balanceBefore(10_000_000L).balanceAfter(15_000_000L).relatedChargeRequestId(20L).build();
        List<AdminActivityType> chargeTypes = List.of(AdminActivityType.CHARGE_REQUEST, AdminActivityType.SELF_BALANCE,
                AdminActivityType.ADMIN_BALANCE);
        when(adminActivityRepository.count(chargeTypes, NO_SEARCH, null, null)).thenReturn(1L);
        when(adminActivityRepository.findKeys(chargeTypes, NO_SEARCH, null, null, 0L, 10))
                .thenReturn(List.of(key(AdminActivityType.CHARGE_REQUEST, 20L)));
        when(chargeRequestRepository.findAllWithUserByRequestIdIn(List.of(20L))).thenReturn(List.of(request));
        when(accountTransactionRepository.findAllByRelatedChargeRequestIdIn(List.of(20L))).thenReturn(List.of(deposit));

        AdminActivityResponse row = adminActivityService.getActivities("charge", NO_SEARCH, null, null, PageRequest.of(0, 10))
                .getContent().get(0);

        assertThat(row.category()).isEqualTo(AdminActivityCategory.CHARGE);
        assertThat(row.occurredAt()).isEqualTo(AT);
        assertThat(row.charge().requestedAmount()).isEqualTo(5_000_000L);
        assertThat(row.charge().status()).isEqualTo(ChargeRequestStatus.APPROVED);
        assertThat(row.charge().decidedByName()).isEqualTo("관리자");
        assertThat(row.charge().depositedAmount()).isEqualTo(5_000_000L);
        assertThat(row.charge().balanceAfter()).isEqualTo(15_000_000L);
        assertThat(row.trade()).isNull();
    }

    @Test
    @DisplayName("주문 한 줄에 체결 금액과 관리자 강제 취소 사유·처리자가 함께 담긴다")
    void tradeRowWithAdminCancel() {
        Order order = Order.builder().account(account).stockCode("005930").stockName("삼성전자")
                .orderType(OrderType.BUY).priceType(PriceType.LIMIT).orderPrice(70_000L).quantity(3).build();
        ReflectionTestUtils.setField(order, "orderId", 30L);
        order.cancel();
        AuditLog cancelLog = AuditLog.builder().adminUserId(99L).adminLoginId("admin").action(AuditLogService.ACTION_ORDER_CANCEL)
                .targetType(AuditLogService.TARGET_ORDER).targetId(30L).reason("이상 주문").build();
        ReflectionTestUtils.setField(cancelLog, "createdAt", AT.plusMinutes(5));
        List<AdminActivityType> tradeTypes = List.of(AdminActivityType.TRADE);
        when(adminActivityRepository.count(tradeTypes, NO_SEARCH, null, null)).thenReturn(1L);
        when(adminActivityRepository.findKeys(tradeTypes, NO_SEARCH, null, null, 0L, 10))
                .thenReturn(List.of(key(AdminActivityType.TRADE, 30L)));
        when(orderRepository.findAllWithUserByOrderIdIn(List.of(30L))).thenReturn(List.of(order));
        when(auditLogRepository.findAllByActionAndTargetTypeAndTargetIdIn(AuditLogService.ACTION_ORDER_CANCEL,
                AuditLogService.TARGET_ORDER, List.of(30L))).thenReturn(List.of(cancelLog));

        AdminActivityResponse row = adminActivityService.getActivities("TRADE", NO_SEARCH, null, null, PageRequest.of(0, 10))
                .getContent().get(0);

        assertThat(row.trade().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(row.trade().cancelReason()).isEqualTo("이상 주문");
        assertThat(row.trade().cancelledByLoginId()).isEqualTo("admin");
        assertThat(row.trade().accountNumber()).isEqualTo("110000000001");
    }

    @Test
    @DisplayName("회원이력 탭은 가입·탈퇴·회원 정지·계좌 정지·관리자 생성을 모으고, 계좌 정지에는 계좌번호가 붙는다")
    void memberTabIncludesAccountStatus() {
        AuditLog accountSuspend = AuditLog.builder().adminUserId(99L).adminLoginId("admin")
                .action(AuditLogService.ACTION_ACCOUNT_STATUS_CHANGE).targetType(AuditLogService.TARGET_ACCOUNT).targetId(10L)
                .beforeValue("ACTIVE").afterValue("SUSPENDED").reason("이상 거래").build();
        ReflectionTestUtils.setField(accountSuspend, "auditLogId", 40L);
        List<AdminActivityType> memberTypes = List.of(AdminActivityType.SIGNUP, AdminActivityType.WITHDRAWAL,
                AdminActivityType.USER_STATUS, AdminActivityType.ACCOUNT_STATUS, AdminActivityType.ADMIN_CREATE);
        AdminSearchConditionDto search = AdminSearchConditionDto.of("tester", AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.EXACT);
        LocalDate day = LocalDate.of(2026, 10, 5);
        when(adminActivityRepository.count(memberTypes, search, day.atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(2L);
        when(adminActivityRepository.findKeys(memberTypes, search, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 0L, 10))
                .thenReturn(List.of(key(AdminActivityType.ACCOUNT_STATUS, 40L), key(AdminActivityType.SIGNUP, 1L)));
        when(auditLogRepository.findAllById(List.of(40L))).thenReturn(List.of(accountSuspend));
        when(accountRepository.findAllById(List.of(10L))).thenReturn(List.of(account));

        Page<AdminActivityResponse> page = adminActivityService.getActivities("MEMBER", search, day, day, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(AdminActivityResponse::activityType)
                .containsExactly(AdminActivityType.ACCOUNT_STATUS, AdminActivityType.SIGNUP);
        assertThat(page.getContent().get(0).member().accountNumber()).isEqualTo("110000000001");
        assertThat(page.getContent().get(0).member().afterValue()).isEqualTo("SUSPENDED");
        assertThat(page.getContent().get(1).member()).isNull();
    }

    @Test
    @DisplayName("없는 탭이거나 시작일이 종료일보다 늦으면 INVALID_INPUT으로 거절하고 조회하지 않는다")
    void rejectsInvalidCategoryAndPeriod() {
        assertThatThrownBy(() -> adminActivityService.getActivities("ADMIN", NO_SEARCH, null, null, PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class)
                .hasMessage(AdminActivityService.INVALID_CATEGORY_MESSAGE);
        assertThatThrownBy(() -> adminActivityService.getActivities("ALL", NO_SEARCH, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 4), PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(adminActivityRepository, never()).findKeys(any(), any(), any(), any(), anyLong(), anyInt());
    }
}
