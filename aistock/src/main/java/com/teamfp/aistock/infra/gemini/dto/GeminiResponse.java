package com.teamfp.aistock.infra.gemini.dto;

import java.util.List;
import java.util.Map;

/**
 * content/functionCalls는 상호 배타적이다 — Gemini가 도구 호출이 필요하다고 판단하면
 * functionCalls만 채워져 오고(content는 null), 바로 텍스트로 답할 수 있으면 content만
 * 채워져 온다(functionCalls는 빈 리스트).
 *
 * <p>functionCalls가 리스트인 이유: Gemini는 한 응답 안에 여러 도구를 "동시에" 요청할 수 있다
 * (parallel function calling, 실제 API로 확인됨). 예를 들어 "삼성전자 실적이랑 뉴스 같이
 * 알려줘" 같은 질문에는 재무제표 도구와 뉴스 도구를 한 응답에서 한꺼번에 요청한다. 이걸
 * 순차적으로 한 번에 하나씩만 처리하면(라운드를 여러 번 돌리면) 필요한 도구 개수만큼 Gemini를
 * 반복 호출하게 되어 API 사용량이 낭비된다 — 요청받은 도구를 전부 한 라운드 안에서 함께
 * 실행하고 결과도 한 번에 되돌려주면, 도구가 몇 개든 상관없이 "도구 실행 1라운드 + 최종 답변
 * 1번"으로 끝낼 수 있다.</p>
 */
public record GeminiResponse(String content, Integer tokenCount, List<FunctionCall> functionCalls) {

    public GeminiResponse(String content, Integer tokenCount) {
        this(content, tokenCount, List.of());
    }

    public boolean isFunctionCall() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

    /**
     * thoughtSignature는 이 functionCall을 나중에 GeminiRequest.FunctionExchange로 되돌려
     * 보낼 때 그대로 echo해야 하는 값이다(GeminiRequest.FunctionExchange 문서 참고).
     */
    public record FunctionCall(String name, Map<String, Object> args, String thoughtSignature) {

        public FunctionCall(String name, Map<String, Object> args) {
            this(name, args, null);
        }
    }
}
