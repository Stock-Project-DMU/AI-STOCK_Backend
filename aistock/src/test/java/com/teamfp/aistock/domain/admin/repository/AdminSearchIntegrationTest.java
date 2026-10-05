package com.teamfp.aistock.domain.admin.repository;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchMatchType;
import com.teamfp.aistock.domain.admin.dto.request.AdminTradeSearchField;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSearchField;
import com.teamfp.aistock.domain.order.entity.Order;
import com.teamfp.aistock.domain.order.entity.OrderType;
import com.teamfp.aistock.domain.order.entity.PriceType;
import com.teamfp.aistock.domain.order.repository.OrderRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 검색 고도화(feat/admin-improvements) — 검색 항목(field)·검색 방식(일치/포함)이 실제 MySQL에서 의도대로
 * 걸러지는지 검증한다. 사전 조건: 로컬 MySQL이 떠 있어야 한다. @Transactional로 넣은 행은 롤백된다.
 */
@SpringBootTest
@Transactional
class AdminSearchIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("EXACT는 아이디가 정확히 같은 회원만, CONTAINS는 아이디가 포함된 회원까지 찾는다")
    void exactVersusContains() {
        String base = "srch" + System.nanoTime();
        saveUser(base, "정확일치테스터");
        saveUser(base + "x", "포함테스터1");
        saveUser("x" + base, "포함테스터2");

        Page<User> exact = searchUsers(base, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.EXACT);
        Page<User> contains = searchUsers(base, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);

        assertThat(exact.getContent()).extracting(User::getLoginId).containsExactly(base);
        assertThat(contains.getContent()).extracting(User::getLoginId)
                .containsExactlyInAnyOrder(base, base + "x", "x" + base);
    }

    @Test
    @DisplayName("회원번호는 포함 검색에서도 정확히 일치하는 번호만 찾는다")
    void userIdMatchesExactly() {
        User user = saveUser("idsrch" + System.nanoTime(), "번호검색테스터");
        String userId = String.valueOf(user.getUserId());

        Page<User> result = searchUsers(userId, AdminUserSearchField.USER_ID, AdminSearchMatchType.CONTAINS);

        assertThat(result.getContent()).extracting(User::getUserId).containsExactly(user.getUserId());
    }

    @Test
    @DisplayName("검색어의 _는 한 글자 와일드카드가 아니라 글자 그대로 찾는다")
    void underscoreIsLiteral() {
        String base = "esc" + System.nanoTime();
        saveUser(base + "_1", "밑줄테스터");
        saveUser(base + "a1", "와일드카드였다면걸림");

        Page<User> result = searchUsers(base + "_1", AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);

        assertThat(result.getContent()).extracting(User::getLoginId).containsExactly(base + "_1");
    }

    @Test
    @DisplayName("검색 항목을 지정하면 다른 항목에만 들어 있는 검색어로는 찾지 않는다")
    void fieldRestrictsColumns() {
        String token = "fld" + System.nanoTime();
        saveUser(token, "다른이름");

        assertThat(searchUsers(token, AdminUserSearchField.NAME, AdminSearchMatchType.CONTAINS).getContent()).isEmpty();
        assertThat(searchUsers(token, AdminUserSearchField.ALL, AdminSearchMatchType.CONTAINS).getContent()).hasSize(1);
    }

    @Test
    @DisplayName("거래 목록을 회원 이름으로 검색할 수 있다")
    void tradeSearchByUserName() {
        long suffix = System.nanoTime();
        String name = "거래이름" + suffix;
        User user = saveUser("trade" + suffix, name);
        Account account = accountRepository.save(Account.builder()
                .user(user)
                .accountName("테스트계좌")
                .accountNumber("S" + (suffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
        Order order = orderRepository.save(Order.builder()
                .account(account)
                .stockCode("005930")
                .stockName("삼성전자")
                .orderType(OrderType.BUY)
                .priceType(PriceType.LIMIT)
                .orderPrice(70_000L)
                .quantity(1)
                .build());

        AdminSearchConditionDto search = AdminSearchConditionDto.of(name, AdminTradeSearchField.NAME, AdminSearchMatchType.EXACT);
        Page<Order> result = orderRepository.searchOrdersWithUser(search.query(), search.pattern(), search.queryId(),
                search.field(), search.exact(), null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Order::getOrderId).containsExactly(order.getOrderId());
    }

    @Test
    @DisplayName("공지 전체 선택용 — 검색 조건을 페이지 없이(unpaged) 조회하면 검색 결과 전체가 나온다")
    void unpagedSearchReturnsAllMatches() {
        String base = "allsel" + System.nanoTime();
        for (int i = 0; i < 25; i++) {
            saveUser(base + "-" + i, "전체선택테스터");
        }
        AdminSearchConditionDto search = AdminSearchConditionDto.of(base, AdminUserSearchField.LOGIN_ID, AdminSearchMatchType.CONTAINS);

        Page<User> result = userRepository.searchUsers(search.query(), search.pattern(), search.queryId(), search.field(),
                search.exact(), null, null, org.springframework.data.domain.Pageable.unpaged());

        assertThat(result.getContent()).hasSize(25);
    }

    private User saveUser(String loginId, String name) {
        return userRepository.save(User.builder()
                .loginId(loginId)
                .name(name)
                .role(Role.USER)
                .isActive(true)
                .build());
    }

    private Page<User> searchUsers(String query, AdminUserSearchField field, AdminSearchMatchType matchType) {
        AdminSearchConditionDto search = AdminSearchConditionDto.of(query, field, matchType);
        return userRepository.searchUsers(search.query(), search.pattern(), search.queryId(), search.field(),
                search.exact(), null, null, PageRequest.of(0, 20));
    }
}
