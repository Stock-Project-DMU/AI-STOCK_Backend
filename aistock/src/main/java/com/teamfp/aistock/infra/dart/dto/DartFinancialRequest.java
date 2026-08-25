package com.teamfp.aistock.infra.dart.dto;

/**
 * Open DART 재무제표 조회 요청 상자.
 * corpCode는 종목코드(6자리)가 아니라 DART 고유번호(8자리, corp_code)다 — 종목코드→corp_code
 * 매핑은 이번 범위 밖이라, 매핑을 가진 호출자가 이미 corp_code로 변환해 넘겨준다고 가정한다.
 */
public record DartFinancialRequest(String corpCode, int year) {
}
