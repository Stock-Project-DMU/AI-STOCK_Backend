package com.teamfp.aistock.domain.ai.dto;

import java.time.LocalDate;

/**
 * 시나리오 곡선의 포인트 하나. value는 종목 주당 시장가(price)가 아니라
 * investmentAmount 복리 계산 결과인 포트폴리오 평가금액 총액이다.
 * date는 매월 1일로 정규화한다.
 */
public record ScenarioPointDto(
        LocalDate date,
        long value
) {
}
