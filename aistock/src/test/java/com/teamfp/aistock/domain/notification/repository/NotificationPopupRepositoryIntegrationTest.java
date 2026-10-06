package com.teamfp.aistock.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.notification.entity.Notice;
import com.teamfp.aistock.domain.notification.entity.NoticeTargetType;
import com.teamfp.aistock.domain.notification.entity.Notification;
import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;

/**
 * 공지 팝업 조회(findActivePopups, feat/admin-improvements)가 실제 DB에서 "내가 받은 공지 + 팝업 기한이 오늘 이후(포함)"만
 * 고르는지 확인한다. 닫은 기록이 없으므로 같은 조건이면 몇 번을 조회해도 계속 나온다. @Transactional로 넣은 행은 롤백된다.
 */
@SpringBootTest
@Transactional
class NotificationPopupRepositoryIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NoticeRepository noticeRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("팝업 기한이 남은 내 공지만 최신 공지순으로 나오고, 다시 조회해도 그대로 나온다")
    void findsOnlyActiveOwnPopups() {
        LocalDate today = LocalDate.now();
        User me = user("popme");
        User other = user("popother");
        Notice endsToday = notice(today);
        Notice endsLater = notice(today.plusDays(3));
        Notification mineToday = receive(me, endsToday);
        Notification mineLater = receive(me, endsLater);
        receive(me, notice(today.minusDays(1)));   // 기한 지남
        receive(me, notice(null));                 // 팝업 아닌 공지
        receive(other, notice(today.plusDays(3))); // 남이 받은 공지

        for (int login = 0; login < 2; login++) {
            assertThat(notificationRepository.findActivePopups(me.getUserId(), today))
                    .extracting(Notification::getNotiId)
                    .containsExactly(mineLater.getNotiId(), mineToday.getNotiId());
        }
    }

    private User user(String prefix) {
        return userRepository.save(User.builder().loginId(prefix + System.nanoTime()).name("팝업테스터")
                .role(Role.USER).isActive(true).build());
    }

    private Notice notice(LocalDate popupEndDate) {
        return noticeRepository.saveAndFlush(Notice.builder().type(NotificationType.SYSTEM).title("공지").content("내용")
                .targetType(NoticeTargetType.ALL).popupEndDate(popupEndDate).build());
    }

    private Notification receive(User user, Notice notice) {
        return notificationRepository.saveAndFlush(Notification.builder().user(user).type(notice.getType())
                .title(notice.getTitle()).content(notice.getContent()).notice(notice).build());
    }
}
