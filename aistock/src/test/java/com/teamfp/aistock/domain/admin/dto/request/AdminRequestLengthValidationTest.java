package com.teamfp.aistock.domain.admin.dto.request;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.teamfp.aistock.domain.account.dto.request.ChargeRequestCreateRequest;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.entity.ChargeRequestStatus;
import com.teamfp.aistock.domain.notification.entity.NotificationType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사유·제목 글자 수 제한(feat/admin-improvements) — DB 컬럼 길이(사유 500, 알림 제목 100/내용 500, 아이디·이름 50,
 * 이메일 100)를 넘는 입력이 서버 오류(500) 대신 요청 검증 단계에서 알기 쉬운 메시지로 거절되는지 확인한다.
 */
class AdminRequestLengthValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("사유는 500자까지 통과하고 501자부터 거절된다")
    void reasonLimit() {
        String ok = "가".repeat(500);
        String tooLong = "가".repeat(501);

        assertThat(validator.validate(new AdminOrderCancelRequest(ok))).isEmpty();
        assertThat(messages(validator.validate(new AdminOrderCancelRequest(tooLong))))
                .containsExactly("취소 사유는 500자를 넘을 수 없습니다.");
        assertThat(messages(validator.validate(new AdminChargeDecisionRequest(ChargeRequestStatus.APPROVED, tooLong))))
                .containsExactly("처리 사유는 500자를 넘을 수 없습니다.");
        assertThat(messages(validator.validate(new AdminAccountAdjustmentRequest(AccountTransactionType.ADMIN_CHARGE, 1_000L, tooLong))))
                .containsExactly("조정 사유는 500자를 넘을 수 없습니다.");
        assertThat(messages(validator.validate(new AdminAccountStatusRequest(AccountStatus.SUSPENDED, tooLong))))
                .containsExactly("사유는 500자를 넘을 수 없습니다.");
        assertThat(messages(validator.validate(new ChargeRequestCreateRequest(1_000L, tooLong))))
                .containsExactly("요청 사유는 500자를 넘을 수 없습니다.");
    }

    @Test
    @DisplayName("알림 제목 100자·내용 500자, 관리자 생성 아이디·이름 50자·이메일 100자를 넘으면 거절된다")
    void titleAndAccountFieldLimits() {
        assertThat(messages(validator.validate(new AdminNotificationRequest("가".repeat(101), "가".repeat(501), NotificationType.SYSTEM, null, null))))
                .containsExactlyInAnyOrder("알림 제목은 100자를 넘을 수 없습니다.", "알림 내용은 500자를 넘을 수 없습니다.");
        String longEmail = "a".repeat(95) + "@x.com";
        assertThat(messages(validator.validate(new AdminCreateRequest("a".repeat(51), "password1", "가".repeat(51), longEmail))))
                .contains("아이디는 50자를 넘을 수 없습니다.", "이름은 50자를 넘을 수 없습니다.", "이메일은 100자를 넘을 수 없습니다.");
    }

    private static <T> Set<String> messages(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(java.util.stream.Collectors.toSet());
    }
}
