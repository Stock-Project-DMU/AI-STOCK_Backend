package com.teamfp.aistock.infra.ls;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>{@code ${ls.local-data-path}} 디렉토리에서 {@code {stockCode}.json} 파일을 읽는다. 파일은
 * LS 원본 TR 필드(hname/price/...)가 아니라 {@link LsCurrentPriceDetailDto} 필드명
 * (stockCode/currentPrice/...)으로 이미 매핑된 형태라, 그 필드에 직접 역직렬화한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ls.mode", havingValue = "mock")
public class LsLocalMarketDataReader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

    @Value("${ls.local-data-path}")
    private String localDataPath;

    /**
     * 종목코드로 로컬 시세 파일({@code {localDataPath}/{stockCode}.json})을 읽는다. 파일이 없거나
     * 파싱에 실패하면 REST 클라이언트들과 동일한 관례로 예외를 던지지 않고 빈 값을 반환한다.
     */
    public Optional<LsCurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        File file = new File(localDataPath, stockCode + ".json");
        if (!file.exists()) {
            log.warn("로컬 시세 파일을 찾지 못함 - stockCode: {}, path: {}", stockCode, file.getPath());
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(file, LsCurrentPriceDetailDto.class));
        } catch (IOException e) {
            log.warn("로컬 시세 파일 파싱 실패 - stockCode: {}, path: {}, 사유: {}", stockCode, file.getPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
