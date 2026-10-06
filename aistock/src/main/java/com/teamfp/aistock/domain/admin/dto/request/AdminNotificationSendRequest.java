package com.teamfp.aistock.domain.admin.dto.request;

import java.time.LocalDate;

import java.util.List;

import com.teamfp.aistock.domain.notification.entity.NotificationType;
import com.teamfp.aistock.domain.user.entity.UserStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 관리자 공지 선택 발송 요청 DTO(POST /api/admin/notifications/send, feat/admin-improvements). 회원관리 목록처럼
 * 회원을 검색·체크해서 보낸다.
 *
 * - targetType=ALL: "전체 선택"(모든 페이지). query/field/matchType/status는 회원 목록 화면의 검색 조건 그대로이고
 *   (GET /api/admin/users와 같은 의미), excludedUserIds는 전체 선택 후 체크를 푼 회원이다. userIds는 무시한다.
 * - targetType=SELECTED: 개별 체크·"현 페이지 선택"으로 고른 userIds에게만 보낸다. 검색 조건·excludedUserIds는 무시한다.
 * 제목·내용 길이 제한은 AdminNotificationRequest와 같다.
 */
public record AdminNotificationSendRequest(
        @NotNull(message = "발송 대상 방식(targetType)은 필수입니다.")
        AdminNotificationTargetType targetType,
        @Size(max = MAX_USER_IDS, message = "한 번에 고를 수 있는 회원은 최대 10,000명입니다.")
        List<Long> userIds,
        @Size(max = MAX_USER_IDS, message = "한 번에 제외할 수 있는 회원은 최대 10,000명입니다.")
        List<Long> excludedUserIds,
        String query,
        AdminUserSearchField field,
        AdminSearchMatchType matchType,
        UserStatus status,
        @NotBlank
        @Size(max = 100, message = "알림 제목은 100자를 넘을 수 없습니다.")
        String title,
        @NotBlank
        @Size(max = 500, message = "알림 내용은 500자를 넘을 수 없습니다.")
        String content,
        @NotNull NotificationType type,
        // 공지 팝업(feat/admin-improvements) — true면 popupEndDate(포함)까지 받는 사람에게 팝업으로도 띄운다.
        Boolean popup,
        LocalDate popupEndDate
) {

    public static final int MAX_USER_IDS = 10_000;
}
