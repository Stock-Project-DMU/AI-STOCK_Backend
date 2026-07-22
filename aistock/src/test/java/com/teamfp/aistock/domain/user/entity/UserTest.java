package com.teamfp.aistock.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — User.status(UserStatus) 관련 엔티티 단위 테스트.
 * DB/Spring 컨텍스트 없이 순수 엔티티 로직만 검증한다.
 */
class UserTest {

    private User newUser() {
        return User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("빌더로 생성하면 status는 기본값 ACTIVE다")
    void defaultStatusIsActive() {
        User user = newUser();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("suspend() 호출 시 status가 SUSPENDED로 바뀐다")
    void suspend_changesStatusToSuspended() {
        User user = newUser();

        user.suspend();

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("activate() 호출 시 정지 상태였던 계정이 다시 ACTIVE로 돌아온다")
    void activate_revertsStatusToActive() {
        User user = newUser();
        user.suspend();

        user.activate();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("suspend()는 본인 탈퇴(isActive)와 별개 — isActive 값에 영향을 주지 않는다")
    void suspend_doesNotAffectIsActive() {
        User user = newUser();

        user.suspend();

        assertThat(user.isActive()).isTrue();
    }
}
