package com.teamfp.aistock.infra.marketdata;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teamfp.aistock.infra.marketdata.dto.CurrentPriceDetailDto;
import com.teamfp.aistock.infra.marketdata.dto.HogaData;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code market-data.mode=mock}에서 외부 시세 데이터 실시간 시세 대신 로컬 파일(또는 HTTP)로 시세·호가를 공급하는
 * 컴포넌트(feature/ls-local-data). 위 10개 REST 클라이언트({@link MarketDataApiClient} 등)와
 * 달리 외부 시세 데이터 API를 호출하지 않으므로(TR코드/Authorization 헤더 불필요) {@link MarketDataApiClientSupport}를
 * 상속하지 않는다.
 *
 * <p>{@code market-data.mode=mock}일 때만 빈으로 생성된다 — {@link MarketDataWebSocketClient}(real 전용)와 정반대
 * 조건이다. {@link MarketDataApiClient}는 이 빈을 {@code Optional}로 주입받아, 존재하면(=mock)
 * 이걸로 대체하고 없으면(=real) 기존 REST 호출을 그대로 쓴다({@code StockSubscriptionManager}가
 * {@code Optional<MarketDataWebSocketClient>}로 mock/real을 구분하는 것과 동일한 패턴). {@code StockService}도
 * 동일한 패턴으로 이 빈을 주입받아 {@code getCurrentPrice()}/{@code getHoga()} 둘 다 mock/real을
 * 분기한다.</p>
 *
 * <p>데이터 원본은 {@code {"005930": {CurrentPriceDetailDto 필드... + HogaData 필드(askPrices
 * 등)...}, "000660": {...}}} 형태의 맵 JSON이다 — 종목 하나당 현재가·호가 필드가 한 JSON 객체에
 * 함께 들어있고, {@link #getCurrentPrice}/{@link #getHoga}는 같은 데이터를 각자 필요한 DTO
 * 타입으로 따로 역직렬화한다(서로 자기 DTO에 없는 필드는 모른 척 무시 — 그래서 아래
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}를 꺼둔다).</p>
 *
 * <p><b>원본을 어디서 읽을지는 {@code market-data.url} 값으로 갈린다</b>(feature/mock-broadcast-remote,
 * 배포 사전검증 단계 추가) — 로컬 개발은 백엔드 프로세스와 데이터 생성기가 같은 파일 시스템을
 * 쓰므로 {@code market-data.local-path} 디렉토리의 {@code market_data.json} 파일을 직접 읽는 게
 * 기본값(빈 문자열)이다. 반면 백엔드를 별도 서버·컨테이너에 배포하면 데이터 생성기가 도는
 * 로컬 PC의 파일 시스템에 더 이상 접근할 수 없으므로, {@code market-data.url}에 데이터
 * 생성기가 노출하는 HTTP 엔드포인트(예: {@code http://<데이터 생성기 호스트>:8081/market-data})를
 * 넣으면 파일 대신 그 URL로 GET 요청해 동일한 JSON을 가져온다. 두 경로 모두 같은 JSON 스키마를
 * 반환하므로 이후 파싱·역직렬화 로직은 완전히 동일하다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "market-data.mode", havingValue = "mock")
public class LocalMarketDataReader {

    private static final String MARKET_DATA_FILE_NAME = "market_data.json";

    // updatedAt(LocalDateTime) 역직렬화를 위해 JavaTimeModule을 반드시 등록해야 한다 — 등록하지
    // 않으면 market_data.json에 updatedAt 값이 있을 때 InvalidDefinitionException으로 실패한다
    // (기존 테스트가 updatedAt을 넣지 않아 발견되지 않았던 버그, 단일 파일 구조 전환 시 수정).
    // FAIL_ON_UNKNOWN_PROPERTIES를 꺼야 한다 — 종목 하나당 JSON 객체 하나에 현재가 필드와 호가
    // 필드가 함께 들어있는데, CurrentPriceDetailDto로 읽을 땐 호가 필드(askPrices 등)가,
    // HogaData로 읽을 땐 현재가 필드(currentPrice 등)가 서로에게 "모르는 필드"이기 때문이다
    // (호가 지원 추가, 2026-09-20).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final MapType MARKET_DATA_MAP_TYPE = OBJECT_MAPPER.getTypeFactory()
            .constructMapType(Map.class, String.class, CurrentPriceDetailDto.class);

    private static final MapType HOGA_MAP_TYPE = OBJECT_MAPPER.getTypeFactory()
            .constructMapType(Map.class, String.class, HogaData.class);

    @Value("${market-data.local-path:}")
    private String localDataPath;

    // 값이 비어있지 않으면(배포 환경) 파일 대신 이 URL로 GET 요청해 같은 JSON을 가져온다.
    // 로컬 개발은 이 값이 비어있어(기본값) 기존과 동일하게 파일을 직접 읽는다. 필드 초기값을
    // 빈 문자열로 둬야 한다 — LocalMarketDataReaderTest처럼 스프링 컨테이너 없이
    // `new LocalMarketDataReader()`로 직접 생성해 @Value 주입이 일어나지 않는 테스트에서
    // 이 필드가 null로 남아 isBlank() 호출 시 NPE가 나는 것을 막는다.
    @Value("${market-data.url:}")
    private String localDataUrl = "";

    // Spring이 관리하는 빈(RestClientConfig의 marketDataRestClientBuilder 등)을 주입받지 않고 직접
    // 생성한다 — MarketDataWebSocketClient의 StandardWebSocketClient와 동일한 이유로, 테스트가 생성자
    // 주입 없이 `new LocalMarketDataReader()`로 만든 뒤 ReflectionTestUtils로 필드만 주입하는
    // 기존 방식을 그대로 유지하기 위함이다. connect/read 타임아웃은 RestClientConfig의 이유와
    // 동일하게(무제한이면 응답 지연 시 요청 스레드가 무한 대기) 직접 설정한다 — 로컬 개발
    // 기본값(local-data-url 빈 문자열)에서는 이 필드가 아예 쓰이지 않는다.
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final RestClient restClient = createRestClient();

    private static RestClient createRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 종목코드로 데이터 원본(파일 또는 URL)에서 해당 종목코드 키의 값을 꺼낸다. 원본을 찾지
     * 못하거나 파싱에 실패하거나 해당 종목코드 키가 없으면 REST 클라이언트들과 동일한 관례로
     * 예외를 던지지 않고 빈 값을 반환한다.
     */
    public Optional<CurrentPriceDetailDto> getCurrentPrice(String stockCode) {
        Map<String, CurrentPriceDetailDto> marketData = readMarketData(MARKET_DATA_MAP_TYPE);
        CurrentPriceDetailDto price = marketData.get(stockCode);
        if (price == null) {
            log.warn("시세 데이터에 종목코드 없음 - stockCode: {}", stockCode);
            return Optional.empty();
        }
        return Optional.of(price);
    }

    /**
     * 데이터 원본 전체를 한 번에 읽어 종목코드→현재가 맵으로 반환한다. {@code MockMarketDataGenerator}
     * (market-data.mode=mock, feature/mock-broadcast)가 구독 중인 다수 종목의 {@code updatedAt} 변경
     * 여부를 매 폴링 주기마다 확인해야 하는데, {@link #getCurrentPrice(String)}을 종목 수만큼
     * 반복 호출하면 같은 원본을 그만큼 반복해서 읽게 되어 한 번만 읽는 전용 메서드를 둔다.
     * 원본을 찾지 못하거나 파싱에 실패하면 다른 메서드와 동일한 관례로 예외를 던지지 않고 빈
     * 맵을 반환한다.
     */
    public Map<String, CurrentPriceDetailDto> getAllCurrentPrices() {
        return readMarketData(MARKET_DATA_MAP_TYPE);
    }

    /**
     * 종목코드로 데이터 원본에서 해당 종목코드 키의 호가 필드(askPrices/askVolumes/bidPrices/
     * bidVolumes)를 꺼낸다. 원본을 찾지 못하거나 파싱에 실패하거나 해당 종목코드 키가 없거나,
     * 있어도 호가 필드 자체가 비어있으면(예: 데이터 생성기가 아직 호가 결과를 채우지 못한
     * 시점) 예외를 던지지 않고 빈 값을 반환한다.
     */
    public Optional<HogaData> getHoga(String stockCode) {
        Map<String, HogaData> marketData = readMarketData(HOGA_MAP_TYPE);
        HogaData hoga = marketData.get(stockCode);
        if (hoga == null || hoga.getAskPrices() == null) {
            log.warn("시세 데이터에 종목코드의 호가 데이터 없음 - stockCode: {}", stockCode);
            return Optional.empty();
        }
        return Optional.of(hoga);
    }

    private <T> Map<String, T> readMarketData(MapType mapType) {
        if (!localDataUrl.isBlank()) {
            return readFromUrl(mapType);
        }
        return readFromFile(mapType);
    }

    private <T> Map<String, T> readFromFile(MapType mapType) {
        File file = new File(localDataPath, MARKET_DATA_FILE_NAME);
        if (!file.exists()) {
            log.warn("로컬 시세 파일을 찾지 못함 - path: {}", file.getPath());
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(file, mapType);
        } catch (IOException e) {
            log.warn("로컬 시세 파일 파싱 실패 - path: {}, 사유: {}", file.getPath(), e.getMessage());
            return Map.of();
        }
    }

    private <T> Map<String, T> readFromUrl(MapType mapType) {
        try {
            String body = restClient.get().uri(localDataUrl).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                log.warn("원격 시세 데이터 응답이 비어있음 - url: {}", localDataUrl);
                return Map.of();
            }
            return OBJECT_MAPPER.readValue(body, mapType);
        } catch (Exception e) {
            log.warn("원격 시세 데이터 조회 실패 - url: {}, 사유: {}", localDataUrl, e.getMessage());
            return Map.of();
        }
    }
}
