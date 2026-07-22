package com.teamfp.aistock.domain.account.entity;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — Account.status(AccountStatus) 관련 엔티티 단위 테스트.
 */
class AccountTest {

    private Account newAccount() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        return Account.builder()
                .user(user)
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
    }

    @Test
    @DisplayName("생성 시 status는 기본값 ACTIVE다")
    void defaultStatusIsActive() {
        Account account = newAccount();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("suspend() 호출 시 status가 SUSPENDED로 바뀐다")
    void suspend_changesStatusToSuspended() {
        Account account = newAccount();

        account.suspend();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    @DisplayName("activate() 호출 시 정지 상태였던 계좌가 다시 ACTIVE로 돌아온다")
    void activate_revertsStatusToActive() {
        Account account = newAccount();
        account.suspend();

        account.activate();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("suspend()는 잔고·보유수량에 영향을 주지 않는다 — 거래만 막는 상태 전환이다")
    void suspend_doesNotAffectBalance() {
        Account account = newAccount();

        account.suspend();

        assertThat(account.getBalance()).isEqualTo(1_000_000L);
    }
}
