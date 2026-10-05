package com.teamfp.aistock.domain.account.repository;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * feature/admin-api-p0 — AccountRepository.searchAccountsWithUser() 실제 MySQL 연동 테스트
 * (관리자 계좌 목록·검색용, ADMIN_API_BACKEND_HANDOFF.md 3.4). 유닛 테스트(AdminAccountServiceTest)는
 * Repository를 Mockito로 대체해서 str/concat 없이도 검증되지만, 실제 JPQL(fetch join + count
 * 쿼리 분리 + query/status 동적 조건)이 MySQL에서 문법 오류 없이 의도한 대로 동작하는지는
 * 실제 DB로만 확인할 수 있다.
 *
 * 사전 조건: 로컬 docker-compose(MySQL)가 떠 있어야 한다. @Transactional로 감싸서 테스트가
 * 끝나면 자동 롤백되므로 기존 데이터에 영향을 주지 않는다.
 */
@SpringBootTest
@Transactional
class AccountRepositoryIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("searchAccountsWithUser는 계좌 소유자 아이디 query와 status 조건을 실제 SQL로 걸러낸다")
    void searchAccountsWithUser_filtersByQueryAndStatus() {
        long uniqueSuffix = System.nanoTime();
        String loginId = "account-search-test-" + uniqueSuffix;
        // 유저당 계좌 1개(accounts.uq_account_user, 2026-10-01)라 계좌 두 개를 같은 유저에 만들 수 없다 —
        // 아이디가 loginId로 시작하는 유저 두 명에게 계좌를 하나씩 만들어 like 검색으로 둘 다 걸리게 한다.
        User user = userRepository.save(User.builder()
                .loginId(loginId + "-a")
                .name("계좌검색테스터")
                .role(Role.USER)
                .isActive(true)
                .build());
        User secondUser = userRepository.save(User.builder()
                .loginId(loginId + "-b")
                .name("계좌검색테스터")
                .role(Role.USER)
                .isActive(true)
                .build());
        Account activeAccount = accountRepository.save(Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("A" + (uniqueSuffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
        Account suspendedAccount = accountRepository.save(Account.builder()
                .user(secondUser)
                .accountName("계좌B")
                .accountNumber("B" + (uniqueSuffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
        suspendedAccount.suspend();

        Page<Account> byLoginId = accountRepository.searchAccountsWithUser(loginId, "%" + (loginId) + "%", null, "ALL", false, null, PageRequest.of(0, 10));
        Page<Account> byLoginIdAndActive = accountRepository.searchAccountsWithUser(loginId, "%" + (loginId) + "%", null, "ALL", false, AccountStatus.ACTIVE, PageRequest.of(0, 10));
        Page<Account> noMatch = accountRepository.searchAccountsWithUser("no-such-login-id-" + uniqueSuffix, "%" + ("no-such-login-id-" + uniqueSuffix) + "%", null, "ALL", false, null, PageRequest.of(0, 10));

        assertThat(byLoginId.getTotalElements()).isEqualTo(2);
        assertThat(byLoginIdAndActive.getContent()).extracting(Account::getAccountId).containsExactly(activeAccount.getAccountId());
        assertThat(byLoginIdAndActive.getContent()).extracting(Account::getAccountId).doesNotContain(suspendedAccount.getAccountId());
        assertThat(noMatch.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("같은 유저에게 계좌를 두 개 만들면 DB 제약(uq_account_user)이 막는다")
    void uniqueAccountPerUser() {
        long uniqueSuffix = System.nanoTime();
        User user = userRepository.save(User.builder()
                .loginId("account-unique-test-" + uniqueSuffix)
                .name("계좌제약테스터")
                .role(Role.USER)
                .isActive(true)
                .build());
        accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("U" + (uniqueSuffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());

        assertThatThrownBy(() -> accountRepository.saveAndFlush(Account.builder()
                .user(user)
                .accountName("계좌B")
                .accountNumber("V" + (uniqueSuffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
