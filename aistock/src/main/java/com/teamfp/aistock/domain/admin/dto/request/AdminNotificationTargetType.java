package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 공지 선택 발송 대상 방식(feat/admin-improvements).
 * - ALL: 화면의 "전체 선택"(모든 페이지) — 지금 검색 조건(query/field/matchType/status)에 맞는 회원 전체에서
 *   excludedUserIds(전체 선택 후 체크를 푼 회원)만 빼고 보낸다. 검색 조건이 없으면 탈퇴하지 않은 회원 전체다.
 * - SELECTED: 개별 체크·"현 페이지 선택"으로 고른 userIds에게만 보낸다.
 */
public enum AdminNotificationTargetType {
    ALL,
    SELECTED
}
