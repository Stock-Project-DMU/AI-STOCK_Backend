package com.teamfp.aistock.infra.ls.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LS증권 Open API "시장 랭킹"류 TR(등락율상위/시가총액상위/거래량상위/거래대금상위/거래급증/
 * 상하한/신고신저가/시간외등락율상위/시간외거래량상위, 총 9개) 응답을 공통 형태로 담는 DTO.
 * 9개 TR이 각자 필드명은 조금씩 다르지만(예: 거래대금상위만 value가 있음) "종목명+현재가+
 * 등락+거래량"이라는 핵심 골격은 동일해, 랭킹 종류별 부가 정보는 extra 필드 하나에 담아
 * describe 단계에서 자연어로 풀어쓴다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsRankingItemDto {

    private int rank;
    private String stockCode;
    private String stockName;
    private Long price;
    private Long changeAmount;
    private Double changeRate;
    private Long volume;
    // 랭킹 종류마다 다른 부가 정보(거래대금상위의 거래대금, 상하한의 연속일수, 신고신저가의
    // 과거 대비 가격 등)를 사람이 읽을 수 있는 한 문장으로 미리 만들어 담는다 — DTO를 9종류로
    // 쪼개는 대신, describe 단계 로직을 클라이언트 쪽(랭킹 종류를 아는 쪽)에 두기 위함이다.
    private String extraInfo;
}
