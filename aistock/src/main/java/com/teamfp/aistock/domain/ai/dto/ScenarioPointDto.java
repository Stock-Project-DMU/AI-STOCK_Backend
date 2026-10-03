package com.teamfp.aistock.domain.ai.dto;

import java.time.LocalDate;

/**
 * 시뮬레이션 곡선의 포인트 하나. value는 종목 주당 시장가(price)가 아니라
 * 시작 금액 + 월 추가 납입을 월 복리로 굴린 포트폴리오 평가금액 총액이다.
 * date는 매월 1일로 정규화한다.
 */
public record ScenarioPointDto(
        LocalDate date,
        long value
) {
}
