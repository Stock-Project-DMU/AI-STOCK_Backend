package com.teamfp.aistock.domain.stock.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.teamfp.aistock.domain.stock.dto.HogaDto;
import com.teamfp.aistock.domain.stock.dto.PriceDirection;
import com.teamfp.aistock.domain.stock.dto.StockPriceDto;
import com.teamfp.aistock.domain.stock.dto.response.HogaResponse;
import com.teamfp.aistock.domain.stock.dto.response.StockPriceResponse;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.redis.RedisStockCacheService;
import com.teamfp.aistock.infra.ls.LsLocalMarketDataReader;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockService {

    private final RedisStockCacheService redisStockCacheService;
    // ls.mode=mock일 때만 존재(LsLocalMarketDataReader의 @ConditionalOnProperty 참고) —
    // LsMarketDataApiClient/StockSubscriptionManager와 동일한 Optional 주입 패턴으로 mock/real을 구분한다.
    private final Optional<LsLocalMarketDataReader> localMarketDataReader;

    /**
     * 현재가 조회.
     *
     * ls.mode=real이면 기존과 동일하게 Redis 캐시(TTL 5초)에서 읽으며, 캐시가 비어있으면(최근
     * tick이 없으면) 종목 자체가 없는 게 아니라 시세를 일시적으로 못 가져오는 상황이므로
     * STOCK_NOT_FOUND가 아닌 STOCK_PRICE_NOT_AVAILABLE을 던진다(OrderService.createMarketOrder()와
     * 동일한 판단, NAMING.md 8-4 참고). 종목코드 자체의 유효성은 별도 종목 마스터가 없어
     * (KNOWN_ISSUES.md 1번) 이 브랜치에서는 검증하지 않는다.
     *
     * ls.mode=mock이면 Redis 대신 LsLocalMarketDataReader로 market_data.json을 직접 읽는다 —
     * mock 모드에서는 LsWebSocketClient가 없어 Redis 캐시가 애초에 채워지지 않기 때문이다
     * (KNOWN_ISSUES.md 2번). LsMarketDataApiClient.getCurrentPrice()와 동일하게, mock
     * 모드에서는 로컬 파일이 유일한 데이터 소스라 Redis로 폴백하지 않는다.
     */
    public StockPriceResponse getCurrentPrice(String stockCode) {
        if (localMarketDataReader.isPresent()) {
            LsCurrentPriceDetailDto local = localMarketDataReader.get().getCurrentPrice(stockCode)
                    .orElseThrow(() -> new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE));
            return toStockPriceResponse(local);
        }

        StockPriceDto dto = redisStockCacheService.getStockPrice(stockCode);
        if (dto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }
        return StockPriceResponse.from(dto);
    }

    private StockPriceResponse toStockPriceResponse(LsCurrentPriceDetailDto dto) {
        return new StockPriceResponse(
                dto.getStockCode(),
                dto.getStockName(),
                dto.getCurrentPrice(),
                dto.getChangeAmount(),
                dto.getChangeRate(),
                PriceDirection.fromChangeRate(dto.getChangeRate()),
                dto.getVolume()
        );
    }

    /**
     * 호가 조회. getCurrentPrice()와 동일한 이유로 캐시 미스는 STOCK_PRICE_NOT_AVAILABLE로 처리한다.
     *
     * ls.mode=mock이어도 이 메서드는 getCurrentPrice()와 달리 여전히 Redis만 본다 —
     * market_data.json(LsCurrentPriceDetailDto)에는 매수/매도 호가 데이터 자체가 없어
     * LsLocalMarketDataReader로 대체할 수 없다(KNOWN_ISSUES.md 4번).
     */
    public HogaResponse getHoga(String stockCode) {
        HogaDto dto = redisStockCacheService.getHogaData(stockCode);
        if (dto == null) {
            throw new CustomException(ErrorCode.STOCK_PRICE_NOT_AVAILABLE);
        }
        return HogaResponse.from(dto);
    }
}
