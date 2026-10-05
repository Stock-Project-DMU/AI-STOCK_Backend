package com.teamfp.aistock.domain.auth.dto.response;

/**
 * 최초 관리자 필요 여부(feat/admin-improvements). adminExists가 false면 프론트가 "최초 관리자 만들기" 모달을 띄운다.
 */
public record InitialAdminStatusResponse(boolean adminExists) {
}
