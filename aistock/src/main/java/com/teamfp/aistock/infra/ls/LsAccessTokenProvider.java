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
 * <p>REST 조회는 만료 60초 전까지 토큰을 공유하며 동시 재발급을 직렬화한다.
 * WebSocket 연결용 토큰은 기존 연결 생명주기를 유지한다.
 */
@Component
public class LsAccessTokenProvider {

    private final RestClient restClient;
    private String cachedToken;
    private long refreshAt;
    private long retryAt;

    @Value("${ls.token-url}")
    private String tokenUrl;

    @Value("${ls.app-key}")
    private String appKey;

    @Value("${ls.app-secret}")
    private String appSecret;

    public LsAccessTokenProvider(@org.springframework.beans.factory.annotation.Qualifier("lsRestClientBuilder") RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public synchronized String issueAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < refreshAt) return cachedToken;
        if (now < retryAt) throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        // Serialize refreshes, including a short cooldown when LS is unavailable.
        retryAt = now + 5_000;
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
        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
        cachedToken = response.getAccessToken();
        long ttlSeconds = Math.max(0, response.getExpiresIn() - 60);
        refreshAt = now + ttlSeconds * 1_000;
        retryAt = 0;
        return cachedToken;
    }
}
