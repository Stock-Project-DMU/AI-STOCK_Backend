package com.teamfp.aistock.domain.user.entity;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UserSuspensionTest {
    @Test void timedSuspensionExpiresWithoutWaitingForScheduler() {
        User user = User.builder().build();
        user.suspend();
        user.setSuspensionDetails("정책 위반", LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(7));
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        user.setSuspensionDetails("정책 위반", LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1));
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
    @Test void indefiniteSuspensionRequiresManualRelease() {
        User user = User.builder().build();
        user.suspend(); user.setSuspensionDetails("사유", null);
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        user.activate();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getSuspensionReason()).isNull();
        assertThat(user.getSuspendedUntil()).isNull();
    }
    // fix/site-polish — 로그인 차단 안내 문구(좁은 빨간 안내 칸에 맞춘 한 줄)
    @Test void suspensionNotice_timedShowsReleaseTimeAndReason() {
        User user = User.builder().build();
        int year = LocalDateTime.now(ZoneId.of("Asia/Seoul")).getYear();
        user.suspend(); user.setSuspensionDetails("욕설", LocalDateTime.of(year, 11, 5, 14, 0));
        assertThat(user.suspensionNotice()).isEqualTo("정지된 계정입니다(해제 예정 11/05 14:00) 정지사유 : 욕설");
        user.setSuspensionDetails("욕설", LocalDateTime.of(year + 1, 1, 2, 9, 30));
        assertThat(user.suspensionNotice()).isEqualTo("정지된 계정입니다(해제 예정 %d/01/02 09:30) 정지사유 : 욕설".formatted(year + 1));
    }
    @Test void suspensionNotice_permanentAndMissingReason() {
        User user = User.builder().build();
        user.suspend(); user.setSuspensionDetails("도배", null);
        assertThat(user.suspensionNotice()).isEqualTo("영구 정지된 계정입니다 정지사유 : 도배");
        user.setSuspensionDetails(null, null);
        assertThat(user.suspensionNotice()).isEqualTo("영구 정지된 계정입니다");
    }
}
