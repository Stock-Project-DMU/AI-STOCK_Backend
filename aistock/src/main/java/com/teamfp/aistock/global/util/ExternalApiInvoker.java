package com.teamfp.aistock.global.util;

import java.util.Arrays;
import java.util.function.Supplier;

import org.springframework.web.client.RestClientException;

import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * infra 계층의 외부 API 클라이언트(GeminiApiClient, DartApiClient, NaverNewsApiClient)가 공통으로
 * 겪는 "RestClientException을 잡아 CustomException(EXTERNAL_API_ERROR)로 변환"하는 처리를
 * 한 곳에 모은다. 클라이언트마다 동일한 catch 블록을 복붙해두면 에러 처리 방식을 바꿀 때
 * (예: 재시도 추가, 응답 코드별 분기) 여러 파일을 일일이 고쳐야 하는 문제가 있었다.
 */
@Slf4j
public final class ExternalApiInvoker {

    private ExternalApiInvoker() {
    }

    /**
     * @param apiCall    실제 RestClient 호출(및 응답 파싱)을 담은 람다
     * @param logMessage 실패 시 남길 로그 메시지(SLF4J 플레이스홀더 {} 포함 가능)
     * @param logArgs    logMessage의 플레이스홀더에 대응하는 값들. 예외 객체는 여기 넣지 않아도
     *                   자동으로 마지막에 덧붙여져 SLF4J가 스택트레이스까지 함께 로깅한다.
     */
    public static <T> T call(Supplier<T> apiCall, String logMessage, Object... logArgs) {
        try {
            return apiCall.get();
        } catch (RestClientException e) {
            Object[] argsWithException = Arrays.copyOf(logArgs, logArgs.length + 1);
            argsWithException[logArgs.length] = e;
            log.error(logMessage, argsWithException);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, e);
        }
    }
}
