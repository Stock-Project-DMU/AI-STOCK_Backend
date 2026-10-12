package com.teamfp.aistock.infra.dart.dto;

/**
 * DART 기업개황(company.json) 중 종목 리서치 "개요" 탭에 보여줄 항목(fix/stock-detail-review). 값은 DART 원문 그대로이고
 * 비어 있는 항목은 null이다. 단, homepageUrl·irUrl은 DART가 "www.samsung.com/sec"처럼 프로토콜 없이 줘 그대로 링크로 걸면
 * 사이트 안의 상대 경로(404)가 되므로 "https://"를 붙여 둔다.
 *
 * @param corpCls         법인 구분(Y 유가증권시장, K 코스닥, N 코넥스, E 기타)
 * @param establishedDate 설립일(YYYYMMDD)
 * @param settlementMonth 결산월(예: "12")
 * @param jurirNo         법인등록번호
 * @param bizrNo          사업자등록번호
 * @param indutyCode      업종코드(통계청 한국표준산업분류 코드 — DART는 업종명을 주지 않는다)
 */
public record DartCompanyResponse(
        String corpCode,
        String corpName,
        String corpNameEng,
        String stockName,
        String ceoName,
        String corpCls,
        String establishedDate,
        String settlementMonth,
        String homepageUrl,
        String irUrl,
        String phoneNumber,
        String address,
        String jurirNo,
        String bizrNo,
        String indutyCode
) {
}
