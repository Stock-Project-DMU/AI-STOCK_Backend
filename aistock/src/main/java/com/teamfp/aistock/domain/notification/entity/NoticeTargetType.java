package com.teamfp.aistock.domain.notification.entity;

/**
 * 관리자 공지를 누구에게 보냈는지(feat/admin-improvements, notices.target_type).
 * SINGLE = 회원 한 명(POST /api/admin/notifications/users/{userId}), ALL = 탈퇴하지 않은 회원 전체(broadcast),
 * SEARCH = 회원 검색 결과 전체에서 일부 제외(send, targetType=ALL), SELECTED = 고른 회원만(send, targetType=SELECTED).
 */
public enum NoticeTargetType {
    SINGLE,
    ALL,
    SEARCH,
    SELECTED
}
