package com.teamfp.aistock.domain.stock.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis {@code stock:hoga:{stockCode}} 캐시(TTL 2초)에 JSON으로 저장되는 실시간 호가창 정보.
 *
 * 매도/매수 각 5단계의 가격·잔량을 담는다. 인덱스 0이 가장 우선순위가 높은 호가
 * (매도는 가장 낮은 가격, 매수는 가장 높은 가격)이며, askPrices[i]와 askVolumes[i],
 * bidPrices[i]와 bidVolumes[i]는 서로 같은 인덱스끼리 짝을 이룬다.
 * 호가는 주가보다 변동이 잦아 TTL을 2초로 더 짧게 잡는다(RedisStockCacheService 참고).
 * Jackson 직렬화/역직렬화를 위해 기본 생성자와 전체 필드 생성자를 둔다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HogaDto {

    private String stockCode;        // 종목코드
    private List<Long> askPrices;    // 매도 호가 1~5단계 (오름차순, 0번째가 가장 낮은=우선순위 높은 매도호가)
    private List<Long> askVolumes;   // 매도 호가별 잔량 (askPrices와 인덱스 대응)
    private List<Long> bidPrices;    // 매수 호가 1~5단계 (내림차순, 0번째가 가장 높은=우선순위 높은 매수호가)
    private List<Long> bidVolumes;   // 매수 호가별 잔량 (bidPrices와 인덱스 대응)
    private LocalDateTime updatedAt; // 이 호가 데이터를 수신/저장한 시각 (캐시 신선도 판단용)
}
