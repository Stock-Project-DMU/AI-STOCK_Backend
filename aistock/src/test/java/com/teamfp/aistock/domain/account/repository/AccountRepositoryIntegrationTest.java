package com.teamfp.aistock.domain.account.repository;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

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
        User user = userRepository.save(User.builder()
                .loginId(loginId)
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
                .user(user)
                .accountName("계좌B")
                .accountNumber("B" + (uniqueSuffix % 10_000_000))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
        suspendedAccount.suspend();

        Page<Account> byLoginId = accountRepository.searchAccountsWithUser(loginId, null, PageRequest.of(0, 10));
        Page<Account> byLoginIdAndActive = accountRepository.searchAccountsWithUser(loginId, AccountStatus.ACTIVE, PageRequest.of(0, 10));
        Page<Account> noMatch = accountRepository.searchAccountsWithUser("no-such-login-id-" + uniqueSuffix, null, PageRequest.of(0, 10));

        assertThat(byLoginId.getTotalElements()).isEqualTo(2);
        assertThat(byLoginIdAndActive.getContent()).extracting(Account::getAccountId).containsExactly(activeAccount.getAccountId());
        assertThat(byLoginIdAndActive.getContent()).extracting(Account::getAccountId).doesNotContain(suspendedAccount.getAccountId());
        assertThat(noMatch.getTotalElements()).isZero();
    }
}
