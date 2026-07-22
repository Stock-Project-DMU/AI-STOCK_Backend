package com.teamfp.aistock.domain.user.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chore/admin-entity-update — UserRepository.countByIsActiveTrue() 실제 MySQL 연동 테스트
 * (관리자 대시보드 "총 사용자 수" 집계용).
 *
 * 사전 조건: 로컬 docker-compose(MySQL 3306)가 떠 있어야 한다. @Transactional로 감싸서
 * 테스트가 끝나면 자동 롤백되므로 기존 데이터에 영향을 주지 않는다. 기존 테이블에 이미
 * 쌓여있는 행이 있을 수 있으므로 절대값이 아니라 "테스트 전/후 증가량(delta)"으로 검증한다.
 */
@SpringBootTest
@Transactional
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("활성 사용자만 카운트하고, 정지·비활성 사용자는 카운트에서 제외한다")
    void countByIsActiveTrue_countsOnlyActiveUsers() {
        long before = userRepository.countByIsActiveTrue();
        long uniqueSuffix = System.nanoTime();

        userRepository.save(User.builder()
                .loginId("active-user-1-" + uniqueSuffix)
                .name("활성유저1")
                .role(Role.USER)
                .isActive(true)
                .build());
        userRepository.save(User.builder()
                .loginId("active-user-2-" + uniqueSuffix)
                .name("활성유저2")
                .role(Role.USER)
                .isActive(true)
                .build());
        userRepository.save(User.builder()
                .loginId("deleted-user-" + uniqueSuffix)
                .name("탈퇴유저")
                .role(Role.USER)
                .isActive(false)
                .build());

        long after = userRepository.countByIsActiveTrue();

        assertThat(after - before).isEqualTo(2);
    }
}
