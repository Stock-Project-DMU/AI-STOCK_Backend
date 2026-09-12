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
}
