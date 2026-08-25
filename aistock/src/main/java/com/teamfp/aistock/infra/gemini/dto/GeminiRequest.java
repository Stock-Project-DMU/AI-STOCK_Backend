package com.teamfp.aistock.infra.gemini.dto;

import java.util.List;
import java.util.Map;

/**
 * Gemini API 호출용 요청 상자.
 * systemInstruction은 대화 내내 고정인 지침(AiPlanningService.SYSTEM_PREAMBLE)을 Gemini의
 * 전용 systemInstruction 필드로 딱 한 번만 보낸다 — 예전에는 이걸 매 턴 prompt에 다시
 * 이어붙여 "이번 사용자 메시지"의 일부인 것처럼 contents 맨 끝에 실었는데, 그러면 실제
 * history가 몇 턴이든 상관없이 모델이 매번 "지금 막 지침을 받은 것"처럼 여겨 "첫 인사만
 * 하라"는 지침을 계속 다시 트리거하는 문제가 있었다(2026-08-07 라이브 테스트로 확인 — 세션
 * 2턴/3턴에도 인사가 반복됨). history는 이전 대화(역할 "user"/"model")를 오래된 순서대로
 * 담고, prompt는 이번에 새로 보낼 메시지(투자성향/보유종목/실제 질문)만 담는다. role을
 * "user"/"model" 문자열로 받는 이유는 infra 계층이 domain.ai의
 * MessageRole(USER/AI) enum을 몰라도 되게 하기 위함이다 — 변환은 AiPlanningService가 한다.
 *
 * tools/functionExchangeRounds는 Gemini의 "함수 호출(Function Calling)" 기능을 쓰기 위한
 * 필드다. tools를 넘기면 Gemini는 이번 사용자 질문에 도구가 필요한지 스스로 판단해서, 필요하면
 * 텍스트 대신 functionCall(도구 이름+인자) 응답을 돌려준다 — 한 응답에 여러 도구를 동시에
 * 요청할 수도 있다(parallel function calling). 그 도구들을 실제로 실행한 결과를
 * functionExchangeRounds에 "라운드" 단위로 담아 "같은 prompt/history로" 다시 generate()를
 * 호출하면, Gemini가 그 결과를 반영한 답변(텍스트 또는 또 다른 functionCall 라운드)을
 * 돌려준다. 바깥 리스트 한 칸이 라운드 하나(한 응답에서 동시에 요청된 도구 호출들)를 의미하며,
 * 실제 API로 검증한 결과 한 라운드에서 요청된 여러 functionCall은 재구성할 때도 "model 턴
 * 하나에 여러 functionCall 파트"로 그룹지어 보내야 한다 — 라운드를 쪼개 model 턴을 여러 개로
 * 나누면 실제 있었던 대화 구조와 달라진다. AiPlanningService.converseWithTools() 참고.
 */
public record GeminiRequest(String systemInstruction, String prompt, List<HistoryTurn> history,
                             List<ToolDeclaration> tools, List<List<FunctionExchange>> functionExchangeRounds,
                             GeminiModel model) {

    /**
     * 하이브리드 모델 구성(2026-08-06) — 단순 작업은 싼 모델, 종합 판단이 필요한 최종 답변만
     * 비싼 모델을 쓴다. {@link com.teamfp.aistock.domain.ai.service.AiPlanningService#converseWithTools}가
     * 도구 판단 라운드(도구 목록을 함께 보내는 라운드)에는 JUDGE, 도구 없이 텍스트 응답을
     * 강제하는 마지막 라운드에는 ANSWER를 골라 넘긴다.
     */
    public enum GeminiModel {
        JUDGE,  // gemini-3.1-flash-lite — 도구 필요 여부 판단, 단순 대화(잡담·되묻기 등)
        ANSWER  // gemini-3.1-flash-lite — 여러 도구 실행 결과+포트폴리오+투자성향을 종합한 최종 답변
    }

    public record HistoryTurn(String role, String content) {
    }

    /**
     * Gemini에 등록할 도구(함수) 하나의 선언. name/parameters는 Gemini가 functionCall 응답을
     * 만들 때 그대로 사용하는 식별자이므로, AiPlanningService가 실제 실행 시 이 name으로
     * 어떤 도구인지 분기한다.
     */
    public record ToolDeclaration(String name, String description, Map<String, ParameterSpec> parameters,
                                   List<String> required) {
    }

    public record ParameterSpec(String type, String description) {
    }

    /**
     * 도구를 이미 한 번 실행한 뒤, 그 결과를 Gemini에 되돌려줄 때 채운다. functionResult는
     * 도구 실행 결과를 담은 임의의 값 묶음이며, Gemini API의 functionResponse.response
     * 필드로 그대로 직렬화된다. thoughtSignature는 원래 functionCall 응답에 함께 온 값을
     * 그대로 echo해야 한다 — 예전에 쓰던 단일 모델(gemini-flash-latest, 실제로는
     * gemini-3.6-flash로 서빙됨, 2026-08-03 실제 키로 확인)은 사고 과정을 이어가는
     * "thinking" 모델이라, functionCall 턴을 재구성할 때 이 값이 없으면 요청 자체가
     * 400(INVALID_ARGUMENT)으로 거부된다. gemini-2.5-flash-lite/flash로 바뀐 뒤(2026-08-06)
     * 이 요구사항이 그대로인지는 GeminiApiClient 클래스 상단 javadoc의 미검증 항목 참고.
     */
    public record FunctionExchange(String functionName, Map<String, Object> args, String thoughtSignature,
                                    Map<String, Object> functionResult) {
    }
}
