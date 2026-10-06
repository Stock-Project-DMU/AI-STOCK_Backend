package com.teamfp.aistock.domain.admin.dto.request;

/**
 * 관리자 목록 검색 조건을 JPQL 파라미터 형태로 풀어 둔 내부 DTO(feat/admin-improvements). 각 Repository의 검색
 * 쿼리는 이 값들을 그대로 받아 "검색 항목(field) + 검색 방식(exact)"을 쿼리 안에서 분기한다.
 *
 * - query: 앞뒤 공백을 뺀 검색어. 비어 있으면 null — 쿼리가 검색 조건 자체를 걸지 않는다.
 * - pattern: CONTAINS(포함) 검색용 LIKE 패턴(%검색어%). 검색어 안의 %, _는 와일드카드가 아니라 글자 그대로
 *   찾도록 ESCAPE_CHAR로 이스케이프한다 — 이전에는 "test_1"이 "testA1"까지 찾는 식으로 결과가 섞였다.
 *   백슬래시는 MySQL 문자열 안에서 따로 해석돼 쿼리가 깨지므로 이스케이프 문자로 '!'를 쓴다.
 * - queryId: 검색어가 숫자로만 이뤄졌을 때의 값. 회원번호·주문번호 같은 ID 항목은 이 값과 정확히 일치할 때만
 *   찾는다("12"로 120번이 나오지 않게). 숫자가 아니면 null이라 ID 항목은 매칭되지 않는다.
 * - field: 검색 항목 enum 이름(예: "ALL", "LOGIN_ID").
 * - exact: true면 EXACT(정확히 일치), false면 CONTAINS(포함).
 */
public record AdminSearchConditionDto(
        String query,
        String pattern,
        Long queryId,
        String field,
        boolean exact
) {

    public static final char ESCAPE_CHAR = '!';
    // Long 범위를 넘는 긴 숫자는 ID가 될 수 없으므로 18자리까지만 ID로 해석한다.
    private static final int MAX_ID_DIGITS = 18;

    public static AdminSearchConditionDto of(String rawQuery, Enum<?> field, AdminSearchMatchType matchType) {
        String fieldName = field == null ? "ALL" : field.name();
        boolean exact = matchType == AdminSearchMatchType.EXACT;
        if (rawQuery == null || rawQuery.isBlank()) {
            return new AdminSearchConditionDto(null, null, null, fieldName, exact);
        }
        String query = rawQuery.trim();
        return new AdminSearchConditionDto(query, "%" + escapeLike(query) + "%", parseId(query), fieldName, exact);
    }

    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                escaped.append(ESCAPE_CHAR);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static Long parseId(String query) {
        if (query.length() > MAX_ID_DIGITS || !query.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(query);
    }
}
