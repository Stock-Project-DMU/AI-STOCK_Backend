package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * infra 계층의 외부 API 클라이언트(GeminiApiClient, DartApiClient, NaverNewsApiClient)가 공통으로
 * 주입받는 RestClient.Builder 빈. 스프링 부트가 이 프로젝트 의존성 조합에서는 RestClient.Builder를
 * 자동 구성해주지 않아(부팅 시 UnsatisfiedDependencyException 발생) 여기서 명시적으로 등록한다.
 * 클라이언트마다 build()로 각자 독립된 RestClient 인스턴스를 만들어 쓴다.
 *
 * connect/read 타임아웃을 반드시 명시한다 — 기본 JDK HttpClient는 타임아웃이 사실상 무제한이라,
 * Gemini/DART/네이버 중 하나가 응답 지연되거나 행(hang)이 걸리면 해당 요청을 처리하던 스레드가
 * 무한 대기하고, 동시 요청이 몰릴 경우 스레드 풀이 고갈되어 로그인·주문 등 무관한 API까지
 * 영향을 받을 수 있다.
 */
@Configuration
public class RestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    @Bean
    @org.springframework.context.annotation.Primary
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);

        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean("lsRestClientBuilder")
    public RestClient.Builder lsRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(3_000);
        return RestClient.builder().requestFactory(factory);
    }
}
