package com.teamfp.aistock.infra.ls.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API 종목 신용/담보 관련 4개 TR(예탁담보융자가능종목현황조회 CLNAQ00100,
 * 증거금율별종목조회 t1411, 신용거래동향 t1921, 종목별대차거래일간추이 t1941) 공통 결과 형태.
 * 4개가 서로 완전히 다른 응답 구조라, 사람이 읽을 문장(detail)으로 미리 만들어 하나의 필드에 담는다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LsStockCreditInfoDto {

    private String stockCode;
    private String detail;
}
