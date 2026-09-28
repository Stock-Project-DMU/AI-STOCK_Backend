package com.teamfp.aistock.domain.ai.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.ai.entity.NewsBriefing;
import com.teamfp.aistock.domain.ai.entity.NewsBriefingSetting;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * refactor/enhancement-plan-b — NewsBriefingSettingRepository.findDueSettings() 실제 MySQL
 * 연동 테스트. 시각 비교(briefingTime <= now 범위 비교, 2026-09-29 우혁 재반려로 정확히-일치
 * 비교에서 변경됨)와 "오늘자 브리핑 없음" NOT EXISTS 서브쿼리가 JPQL 문자열 안에만 있어
 * 자바 코드로는 더 이상 직접 검증할 수 없으므로(2차 코드리뷰 발견 — AiNewsServiceTest는
 * findDueSettings() 자체를 mock으로 대체해 이 로직을 더 이상 통과시키지 않는다), 실제 DB로
 * 쿼리 자체를 검증한다.
 * 사전 조건: 로컬 docker-compose(MySQL 3306)가 떠 있어야 한다.
 * @Transactional로 감싸서 테스트 종료 후 자동 롤백된다.
 */
@SpringBootTest
@Transactional
class NewsBriefingSettingRepositoryIntegrationTest {

    @Autowired
    private NewsBriefingSettingRepository newsBriefingSettingRepository;
    @Autowired
    private NewsBriefingRepository newsBriefingRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String suffix) {
        return userRepository.save(User.builder()
                .loginId("news-setting-repo-test-" + suffix)
                .name("뉴스설정레포테스터")
                .role(Role.USER)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("briefingTime이 지금 이 순간보다 이전이거나 같은 설정만 반환하고, 아직 안 지난 시각은 제외한다")
    void findDueSettings_matchesSettingsAtOrBeforeNow() {
        LocalTime now = LocalTime.of(9, 30, 15);
        LocalDate today = LocalDate.now();

        User earlierUser = newUser(String.valueOf(System.nanoTime()));
        NewsBriefingSetting earlier = newsBriefingSettingRepository.save(NewsBriefingSetting.builder()
                .user(earlierUser).outletDomain("hankyung.com").briefingTime(now.minusMinutes(5)).build());

        User exactUser = newUser(String.valueOf(System.nanoTime()));
        NewsBriefingSetting exact = newsBriefingSettingRepository.save(NewsBriefingSetting.builder()
                .user(exactUser).outletDomain("mk.co.kr").briefingTime(now).build());

        User notYetDueUser = newUser(String.valueOf(System.nanoTime()));
        newsBriefingSettingRepository.save(NewsBriefingSetting.builder()
                .user(notYetDueUser).outletDomain("hankyung.com").briefingTime(now.plusMinutes(5)).build());

        List<NewsBriefingSetting> due = newsBriefingSettingRepository.findDueSettings(now, today);

        assertThat(due).extracting(NewsBriefingSetting::getSettingId)
                .containsExactlyInAnyOrder(earlier.getSettingId(), exact.getSettingId());
    }

    @Test
    @DisplayName("시각은 일치해도 오늘자 브리핑을 이미 받은 사용자만 제외하고, 같은 시각의 다른 사용자는 그대로 반환한다")
    void findDueSettings_excludesOnlyUsersAlreadyBriefedToday() {
        // NOT EXISTS 서브쿼리가 진짜로 사용자별로 스코프되는지(n.user.userId = u.userId 조인이
        // 실제로 걸리는지) 검증하려면 같은 시각을 고른 사용자가 최소 2명 있어야 한다 — 한 명만
        // 놓고 테스트하면 조인 조건이 깨져도(예: 실수로 전체 NewsBriefing 대상으로 검사) 우연히
        // 통과할 수 있다(3차 코드리뷰 발견). 07:00:00은 NewsBriefingSetting.DEFAULT_BRIEFING_TIME과
        // 겹쳐 공유 DB의 다른 데이터와 충돌할 수 있어 일부러 쓰지 않는다.
        LocalTime dueTime = LocalTime.of(3, 17, 42);
        LocalDate today = LocalDate.now();

        User alreadyBriefed = newUser(String.valueOf(System.nanoTime()));
        newsBriefingSettingRepository.save(NewsBriefingSetting.builder()
                .user(alreadyBriefed).outletDomain("hankyung.com").briefingTime(dueTime).build());
        newsBriefingRepository.save(NewsBriefing.builder()
                .user(alreadyBriefed).outletDomain("hankyung.com").briefingDate(today)
                .content("오늘자 브리핑").sourceLinksJson("[]").build());

        User notYetBriefed = newUser(String.valueOf(System.nanoTime()));
        NewsBriefingSetting stillDue = newsBriefingSettingRepository.save(NewsBriefingSetting.builder()
                .user(notYetBriefed).outletDomain("mk.co.kr").briefingTime(dueTime).build());

        List<NewsBriefingSetting> due = newsBriefingSettingRepository.findDueSettings(dueTime, today);

        assertThat(due).extracting(NewsBriefingSetting::getSettingId).containsExactly(stillDue.getSettingId());
    }
}
