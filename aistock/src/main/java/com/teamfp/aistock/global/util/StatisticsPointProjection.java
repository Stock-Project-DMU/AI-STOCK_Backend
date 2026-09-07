package com.teamfp.aistock.global.util;

/**
 * 기간별 통계 네이티브 쿼리(UserRepository.aggregateUserSignups(),
 * OrderRepository.aggregateOrderCounts()/aggregateExecutedAmounts())의 결과 프로젝션.
 * 세 쿼리 모두 "period(집계 단위 문자열) + value(건수 또는 합계)" 모양이 동일해 인터페이스
 * 하나를 공유한다 — 각 쿼리의 SELECT 별칭(as period, as value)이 이 getter 이름과 일치해야
 * Spring Data JPA가 네이티브 쿼리 결과를 자동으로 이 인터페이스에 매핑한다.
 *
 * global/util에 둔 이유(코드리뷰 반영, 2026-09): 원래 domain/admin/dto/projection에 있었는데,
 * 이 프로젝션을 실제로 만들어 반환하는 곳은 order/user 도메인의 Repository라서
 * OrderRepository/UserRepository가 admin 패키지를 import해야 했다 — admin은 다른 도메인을
 * 조합만 하고 다른 도메인은 admin을 모른다는 CLAUDE.md 4번의 단방향 설계와 반대 방향이었다.
 * "period+value" 모양 자체는 admin 고유 개념이 아니라 순수한 집계 결과 형태라 global/util로
 * 옮겨 order/user가 admin을 몰라도 되게 했다.
 */
public interface StatisticsPointProjection {
    String getPeriod();

    long getValue();
}
