package com.teamfp.aistock.infra.ls;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;
import com.teamfp.aistock.global.util.ExternalApiInvoker;
import com.teamfp.aistock.infra.ls.dto.LsTokenResponse;

/**
 * LS증권 Open API REST 조회(t1102/t1716/t3401/t3202 등)가 공통으로 필요로 하는 접근토큰을
 * 발급한다. {@link LsMarketDataApiClient}(현재가), {@link LsInvestorTrendApiClient}(외국인/기관
 * 매매동향), {@link LsInvestInfoApiClient}(투자의견/증시일정)가 각자 {@code issueAccessToken()}을
 * 복붙해 갖고 있던 걸 하나로 모았다 — REST 조회 클라이언트가 하나둘 늘어날 때마다 같은 로직이
 * 계속 늘어나는 걸 막기 위함이다.
 *
 * <p>{@link LsWebSocketClient}는 이 컴포넌트를 쓰지 않는다 — WebSocket은 연결 시점에 한 번만
 * 토큰을 받아 세션 내내 재사용하는 반면, 이 REST 조회들은 매 요청마다 새로 토큰을 발급받는
 * 단발성 호출이라 성격이 다르고, {@code ls.mode}(모의/실전) 조건과도 무관하게 항상 동작해야
 * 하기 때문이다(LsMarketDataApiClient 클래스 주석 참고).</p>
 */
@Component
public class LsAccessTokenProvider {

    private final RestClient restClient;

    @Value("${ls.token-url}")
    private String tokenUrl;

    @Value("${ls.app-key}")
    private String appKey;

    @Value("${ls.app-secret}")
    private String appSecret;

    public LsAccessTokenProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public String issueAccessToken() {
        String formBody = "grant_type=client_credentials"
                + "&appkey=" + URLEncoder.encode(appKey, StandardCharsets.UTF_8)
                + "&appsecretkey=" + URLEncoder.encode(appSecret, StandardCharsets.UTF_8)
                + "&scope=oob";
        LsTokenResponse response = ExternalApiInvoker.call(() -> restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(formBody)
                        .retrieve()
                        .body(LsTokenResponse.class),
                "LS 토큰 발급 실패");
        if (response == null || response.getAccessToken() == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
        return response.getAccessToken();
    }
}
