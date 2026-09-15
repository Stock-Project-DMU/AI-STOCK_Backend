package com.teamfp.aistock.domain.user.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.global.util.StatisticsPointProjection;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;

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

    @Test
    @DisplayName("searchUsers()는 query(아이디 부분일치) + status + role 조건을 실제 SQL로 걸러낸다")
    void searchUsers_filtersByQueryStatusAndRole() {
        // 유닛 테스트(AdminUserServiceTest)는 Repository를 Mockito로 대체해서 str(u.userId)/
        // concat('%',:query,'%') 같은 JPQL 표현이 실제 MySQL에서 문법 오류 없이 동작하고
        // 의도한 대로 걸러내는지는 검증하지 못한다 — 여기서 실제 DB로 그 부분을 검증한다.
        long uniqueSuffix = System.nanoTime();
        String loginIdPrefix = "search-test-" + uniqueSuffix;

        userRepository.save(User.builder()
                .loginId(loginIdPrefix + "-active-user")
                .name("검색테스터1")
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build());
        userRepository.save(User.builder()
                .loginId(loginIdPrefix + "-suspended-user")
                .name("검색테스터2")
                .role(Role.USER)
                .status(UserStatus.SUSPENDED)
                .isActive(true)
                .build());
        userRepository.save(User.builder()
                .loginId(loginIdPrefix + "-active-admin")
                .name("검색테스터3")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .isActive(true)
                .build());

        Page<User> queryOnly = userRepository.searchUsers(loginIdPrefix, null, null, PageRequest.of(0, 10));
        Page<User> activeUsersOnly = userRepository.searchUsers(loginIdPrefix, UserStatus.ACTIVE, Role.USER, PageRequest.of(0, 10));
        Page<User> noMatch = userRepository.searchUsers("no-such-login-id-" + uniqueSuffix, null, null, PageRequest.of(0, 10));

        assertThat(queryOnly.getTotalElements()).isEqualTo(3);
        assertThat(activeUsersOnly.getContent()).hasSize(1);
        assertThat(activeUsersOnly.getContent().get(0).getLoginId()).isEqualTo(loginIdPrefix + "-active-user");
        assertThat(noMatch.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("aggregateUserSignups는 실제 MySQL에서 DATE_FORMAT 집계가 SQL 오류 없이 동작하고 기간 내 가입자 수를 센다")
    void aggregateUserSignups_executesNativeDateFormatQuery() {
        // 유닛 테스트(AdminStatisticsServiceTest)는 Repository를 Mockito로 대체해서
        // date_format(created_at, :pattern) 같은 MySQL 전용 함수를 쓰는 네이티브 쿼리가 실제로
        // 문법 오류 없이 실행되고 StatisticsPointProjection으로 정상 매핑되는지는 검증하지 못한다.
        long uniqueSuffix = System.nanoTime();
        LocalDateTime from = LocalDateTime.now().minusMinutes(1);

        userRepository.save(User.builder()
                .loginId("stat-user-1-" + uniqueSuffix)
                .name("통계유저1")
                .role(Role.USER)
                .isActive(true)
                .build());
        userRepository.save(User.builder()
                .loginId("stat-user-2-" + uniqueSuffix)
                .name("통계유저2")
                .role(Role.USER)
                .isActive(true)
                .build());

        LocalDateTime to = LocalDateTime.now().plusMinutes(1);
        List<StatisticsPointProjection> result = userRepository.aggregateUserSignups("%Y-%m-%d", from, to);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getPeriod()).matches("\\d{4}-\\d{2}-\\d{2}");
        long totalInRange = result.stream().mapToLong(StatisticsPointProjection::getValue).sum();
        assertThat(totalInRange).isGreaterThanOrEqualTo(2);
    }
}
