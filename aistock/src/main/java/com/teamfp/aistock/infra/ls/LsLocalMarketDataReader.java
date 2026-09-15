package com.teamfp.aistock.infra.ls;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teamfp.aistock.infra.ls.dto.LsCurrentPriceDetailDto;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code ls.mode=mock}에서 LS 실시간 시세 대신 로컬 파일로 시세를 공급하는 컴포넌트
 * (feature/ls-local-data). 위 10개 REST 클라이언트({@link LsMarketDataApiClient} 등)와 달리
 * LS API를 호출하지 않으므로(TR코드/Authorization 헤더 불필요) {@link LsApiClientSupport}를
 * 상속하지 않는다.
 *
 * <p>{@code ls.mode=mock}일 때만 빈으로 생성된다 — {@link LsWebSocketClient}(real 전용)와 정반대
 * 조건이다. {@link LsMarketDataApiClient}는 이 빈을 {@code Optional}로 주입받아, 존재하면(=mock)
 * 이걸로 대체하고 없으면(=real) 기존 REST 호출을 그대로 쓴다({@code StockSubscriptionManager}가
 * {@code Optional<LsWebSocketClient>}로 mock/real을 구분하는 것과 동일한 패턴).</p>
 *
 * <p>{@code ${ls.local-data-path}} 디렉토리의 {@code market_data.json} 단일 파일을 읽는다
 * (local-market-data-generator가 코스피·코스닥 상위 100종목을 이 파일 하나에 통합 저장하는
 * 구조로 바뀌어, 종목별 {@code {stockCode}.json} 파일 구조를 대체한다). 파일은
 * {@code {"005930": {LsCurrentPriceDetailDto 필드...}, "000660": {...}}} 형태의 맵이라,
 * 종목코드를 키로 꺼내 그 필드에 역직렬화한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ls.mode", havingValue = "mock")
public class LsLocalMarketDataReader {

    private static final String MARKET_DATA_FILE_NAME = "market_data.json";

    // updatedAt(LocalDateTime) 역직렬화를 위해 JavaTimeModule을 반드시 등록해야 한다 — 등록하지
    // 않으면 market_data.json에 updatedAt 값이 있을 때 InvalidDefinitionException으로 실패한다
    // (기존 테스트가 updatedAt을 넣지 않아 발견되지 않았던 버그, 단일 파일 구조 전환 시 수정).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
            .registerModule(new JavaTimeModule());

    private static final MapType MARKET_DATA_MAP_TYPE = OBJECT_MAPPER.getTypeFactory()
            .constructMapType(Map.class, String.class, LsCurrentPriceDetailDto.class);

    @Value("${ls.local-data-path}")
    private String localDataPath;

    /**
     * 종목코드로 {@code {localDataPath}/market_data.json}을 읽어 해당 종목코드 키의 값을 꺼낸다.
     * 파일이 없거나 파싱에 실패하거나 해당 종목코드 키가 없으면 REST 클라이언트들과 동일한
     * 관례로 예외를 던지지 않고 빈 값을 반환한다.
     */
    public Optional<LsCurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        File file = new File(localDataPath, MARKET_DATA_FILE_NAME);
        if (!file.exists()) {
            log.warn("로컬 시세 파일을 찾지 못함 - stockCode: {}, path: {}", stockCode, file.getPath());
            return Optional.empty();
        }
        try {
            Map<String, LsCurrentPriceDetailDto> marketData = OBJECT_MAPPER.readValue(file, MARKET_DATA_MAP_TYPE);
            LsCurrentPriceDetailDto price = marketData.get(stockCode);
            if (price == null) {
                log.warn("로컬 시세 파일에 종목코드 없음 - stockCode: {}, path: {}", stockCode, file.getPath());
                return Optional.empty();
            }
            return Optional.of(price);
        } catch (IOException e) {
            log.warn("로컬 시세 파일 파싱 실패 - stockCode: {}, path: {}, 사유: {}", stockCode, file.getPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
