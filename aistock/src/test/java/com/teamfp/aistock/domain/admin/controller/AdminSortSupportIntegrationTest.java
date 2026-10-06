package com.teamfp.aistock.domain.admin.controller;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.ChargeRequest;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.ChargeRequestRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountTransactionSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeHistorySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeRequestSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeRequestSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminTradeSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminTradeSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSortType;
import com.teamfp.aistock.domain.admin.service.AdminAccountTransactionService;
import com.teamfp.aistock.domain.admin.service.AdminInquiryService;
import com.teamfp.aistock.domain.admin.service.AdminNoticeService;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 목록 정렬(feat/admin-improvements, AdminSortSupport)이 실제 MySQL 검색 쿼리에 그대로 적용되는지 검증한다 —
 * 특히 거래 금액 정렬은 주문가 × 수량 식을 쿼리에 그대로 넣는다. 사전 조건: 로컬 MySQL이 떠 있어야 한다.
 * @Transactional로 넣은 행은 롤백된다. 이 테스트가 만든 데이터만 검색해서 검증한다.
 */
@SpringBootTest
@Transactional
class AdminSortSupportIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ChargeRequestRepository chargeRequestRepository;

    @Autowired
    private AdminAccountTransactionService adminAccountTransactionService;
    @Autowired
    private AdminInquiryService adminInquiryService;
    @Autowired
    private AdminNoticeService adminNoticeService;

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    @Test
    @DisplayName("거래 금액 정렬은 주문가 × 수량 기준이다(주문가만 크다고 앞에 오지 않는다)")
    void tradeAmountSort() {
        Account account = account("trd");
        Order small = order(account, 100_000L, 1);   // 10만원
        Order large = order(account, 30_000L, 10);   // 30만원 — 주문가는 작지만 금액이 크다
        AdminSearchConditionDto search = AdminSearchConditionDto.of(account.getAccountNumber(),
                AdminTradeSearchField.ACCOUNT_NUMBER, AdminSearchMatchType.EXACT);

        List<Long> desc = trades(search, AdminSortType.AMOUNT_DESC);
        List<Long> asc = trades(search, AdminSortType.AMOUNT_ASC);

        assertThat(desc).containsExactly(large.getOrderId(), small.getOrderId());
        assertThat(asc).containsExactly(small.getOrderId(), large.getOrderId());
    }

    @Test
    @DisplayName("회원 이름순, 충전 요청 금액순, 계좌 잔고순이 쿼리에 적용된다")
    void userChargeAccountSorts() {
        String prefix = "srt" + System.nanoTime();
        User b = user(prefix + "b", "나회원");
        User a = user(prefix + "a", "가회원");
        AdminSearchConditionDto userSearch = AdminSearchConditionDto.of(prefix, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);
        assertThat(userRepository.searchUsers(userSearch.query(), userSearch.pattern(), userSearch.queryId(), userSearch.field(),
                        userSearch.exact(), null, null, AdminSortSupport.users(FIRST_PAGE, AdminUserSortType.NAME, null, null)).getContent())
                .extracting(User::getUserId).containsExactly(a.getUserId(), b.getUserId());

        Account account = accountOf(a, 5_000_000L);
        accountOf(b, 9_000_000L);
        ChargeRequest smallRequest = chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(1_000L).reason("작은").build());
        ChargeRequest largeRequest = chargeRequestRepository.save(ChargeRequest.builder().account(account).amount(9_000L).reason("큰").build());
        AdminSearchConditionDto chargeSearch = AdminSearchConditionDto.of(account.getAccountNumber(),
                AdminChargeRequestSearchField.ACCOUNT_NUMBER, AdminSearchMatchType.EXACT);
        assertThat(chargeRequestRepository.searchWithAccountAndUser(chargeSearch.query(), chargeSearch.pattern(),
                        chargeSearch.queryId(), chargeSearch.field(), chargeSearch.exact(), null,
                        AdminSortSupport.chargeRequests(FIRST_PAGE, AdminSortType.AMOUNT_DESC, null, null)).getContent())
                .extracting(ChargeRequest::getRequestId).containsExactly(largeRequest.getRequestId(), smallRequest.getRequestId());

        AdminSearchConditionDto accountSearch = AdminSearchConditionDto.of(prefix, AdminAccountSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);
        assertThat(accountRepository.searchAccountsWithUser(accountSearch.query(), accountSearch.pattern(), accountSearch.queryId(),
                        accountSearch.field(), accountSearch.exact(), null,
                        AdminSortSupport.accounts(FIRST_PAGE, AdminAccountSortType.BALANCE_DESC, null, null)).getContent())
                .extracting(Account::getBalance).containsExactly(9_000_000L, 5_000_000L);
    }

    @Test
    @DisplayName("열 제목 정렬 — 모든 열이 오름·내림 양방향으로 쿼리 오류 없이 실행된다")
    void everyColumnRuns() {
        AdminSearchConditionDto none = AdminSearchConditionDto.of(null, AdminUserSearchField.ALL, AdminSearchMatchType.CONTAINS);
        for (Sort.Direction direction : Sort.Direction.values()) {
            for (AdminUserSortColumn column : AdminUserSortColumn.values()) {
                userRepository.searchUsers(none.query(), none.pattern(), none.queryId(), none.field(), none.exact(), null, null,
                        AdminSortSupport.users(FIRST_PAGE, null, column, direction));
            }
            for (AdminTradeSortColumn column : AdminTradeSortColumn.values()) {
                orderRepository.searchOrdersWithUser(none.query(), none.pattern(), none.queryId(), none.field(), none.exact(),
                        null, null, null, null, null, null, AdminSortSupport.trades(FIRST_PAGE, null, column, direction));
            }
            for (AdminChargeRequestSortColumn column : AdminChargeRequestSortColumn.values()) {
                chargeRequestRepository.searchWithAccountAndUser(none.query(), none.pattern(), none.queryId(), none.field(),
                        none.exact(), null, AdminSortSupport.chargeRequests(FIRST_PAGE, null, column, direction));
            }
            for (AdminAccountSortColumn column : AdminAccountSortColumn.values()) {
                accountRepository.searchAccountsWithUser(none.query(), none.pattern(), none.queryId(), none.field(),
                        none.exact(), null, AdminSortSupport.accounts(FIRST_PAGE, null, column, direction));
            }
            for (AdminInquirySortColumn column : AdminInquirySortColumn.values()) {
                adminInquiryService.getInquiries(none, null, AdminSortSupport.inquiries(FIRST_PAGE, null, column, direction));
            }
            for (AdminInquirySortType sortType : AdminInquirySortType.values()) {
                adminInquiryService.getInquiries(none, null, AdminSortSupport.inquiries(FIRST_PAGE, sortType, null, null));
            }
            for (AdminNoticeSortColumn column : AdminNoticeSortColumn.values()) {
                adminNoticeService.getNotices(AdminSearchConditionDto.of(null, AdminNoticeSearchField.ALL, AdminSearchMatchType.CONTAINS),
                        null, AdminSortSupport.notices(FIRST_PAGE, null, column, direction));
            }
            for (AdminChargeHistorySortColumn column : AdminChargeHistorySortColumn.values()) {
                adminAccountTransactionService.getChargeDeductionHistory(null,
                        AdminSearchConditionDto.of(null, AdminAccountTransactionSearchField.ALL, AdminSearchMatchType.CONTAINS),
                        null, column, direction, FIRST_PAGE);
            }
        }
    }

    @Test
    @DisplayName("열 제목 정렬 — 정렬 선택칸보다 우선하고 방향이 적용된다(거래 종목명·금액, 회원 아이디)")
    void columnOverridesSortBy() {
        Account account = account("col");
        Order samsung = orderOf(account, "삼성전자", 100_000L, 1);
        Order kakao = orderOf(account, "카카오", 30_000L, 10);
        AdminSearchConditionDto search = AdminSearchConditionDto.of(account.getAccountNumber(),
                AdminTradeSearchField.ACCOUNT_NUMBER, AdminSearchMatchType.EXACT);

        assertThat(tradesByColumn(search, AdminTradeSortColumn.STOCK, Sort.Direction.ASC))
                .containsExactly(samsung.getOrderId(), kakao.getOrderId());
        assertThat(tradesByColumn(search, AdminTradeSortColumn.STOCK, Sort.Direction.DESC))
                .containsExactly(kakao.getOrderId(), samsung.getOrderId());
        assertThat(tradesByColumn(search, AdminTradeSortColumn.AMOUNT, Sort.Direction.ASC))
                .containsExactly(samsung.getOrderId(), kakao.getOrderId());

        String prefix = "colu" + System.nanoTime();
        User b = user(prefix + "b", "가회원");
        User a = user(prefix + "a", "나회원");
        AdminSearchConditionDto userSearch = AdminSearchConditionDto.of(prefix, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);
        // sortBy=NAME(이름순)이라도 열 정렬(아이디 내림차순)이 이긴다
        assertThat(userRepository.searchUsers(userSearch.query(), userSearch.pattern(), userSearch.queryId(), userSearch.field(),
                        userSearch.exact(), null, null,
                        AdminSortSupport.users(FIRST_PAGE, AdminUserSortType.NAME, AdminUserSortColumn.LOGIN_ID, Sort.Direction.DESC))
                .getContent())
                .extracting(User::getUserId).containsExactly(b.getUserId(), a.getUserId());
    }

    private List<Long> tradesByColumn(AdminSearchConditionDto search, AdminTradeSortColumn column, Sort.Direction direction) {
        return orderRepository.searchOrdersWithUser(search.query(), search.pattern(), search.queryId(), search.field(),
                        search.exact(), null, null, null, null, null, null, AdminSortSupport.trades(FIRST_PAGE, null, column, direction))
                .getContent().stream().map(Order::getOrderId).toList();
    }

    private List<Long> trades(AdminSearchConditionDto search, AdminSortType sortType) {
        return orderRepository.searchOrdersWithUser(search.query(), search.pattern(), search.queryId(), search.field(),
                        search.exact(), null, null, null, null, null, null, AdminSortSupport.trades(FIRST_PAGE, sortType, null, null))
                .getContent().stream().map(Order::getOrderId).toList();
    }

    private User user(String loginId, String name) {
        return userRepository.save(User.builder().loginId(loginId).name(name).role(Role.USER).isActive(true).build());
    }

    private Account account(String prefix) {
        return accountOf(user(prefix + System.nanoTime(), "정렬테스터"), 1_000_000L);
    }

    private Account accountOf(User user, long balance) {
        return accountRepository.save(Account.builder().user(user).accountName("계좌")
                .accountNumber("S" + (System.nanoTime() % 100_000_000)).openedAt(LocalDate.now())
                .baseBalance(balance).balance(balance).build());
    }

    private Order order(Account account, long orderPrice, int quantity) {
        return orderOf(account, "삼성전자", orderPrice, quantity);
    }

    private Order orderOf(Account account, String stockName, long orderPrice, int quantity) {
        return orderRepository.save(Order.builder().account(account).stockCode("005930").stockName(stockName)
                .orderType(OrderType.BUY).priceType(PriceType.LIMIT).orderPrice(orderPrice).quantity(quantity).build());
    }
}
