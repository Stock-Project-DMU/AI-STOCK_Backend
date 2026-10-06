package com.teamfp.aistock.domain.admin.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;

import jakarta.persistence.Query;

/**
 * 여러 테이블을 UNION으로 합치는 관리자 네이티브 조회(충전·차감 이력, 전체 활동 기록)에서 쓰는 검색 조건 조각
 * (feat/admin-improvements). JPQL 검색 쿼리(UserRepository.searchUsers 등)와 같은 규칙이다 — 검색 항목(field)을
 * 고르고, 숫자 ID 항목은 정확히 일치, 문자열 항목은 EXACT면 =, CONTAINS면 LIKE(%·_는 '!'로 이스케이프).
 *
 * 검색어가 없으면 조건을 아예 붙이지 않는다(네이티브 쿼리에 null 파라미터를 넘기지 않기 위해서다).
 */
final class AdminNativeSearchSupport {

    private AdminNativeSearchSupport() {
    }

    /**
     * @param textColumns 검색 항목 이름 → 문자열 컬럼(예: "LOGIN_ID" → "login_id")
     * @param idColumns   검색 항목 이름 → 숫자 ID 컬럼(예: "USER_ID" → "user_id")
     */
    static SearchClause clause(AdminSearchConditionDto search, Map<String, String> textColumns, Map<String, String> idColumns) {
        if (search == null || search.query() == null) {
            return SearchClause.NONE;
        }
        List<String> parts = new ArrayList<>();
        boolean all = "ALL".equals(search.field());
        boolean usesQueryId = false;
        boolean usesText = false;
        for (Map.Entry<String, String> entry : idColumns.entrySet()) {
            if ((all || entry.getKey().equals(search.field())) && search.queryId() != null) {
                parts.add(entry.getValue() + " = :queryId");
                usesQueryId = true;
            }
        }
        for (Map.Entry<String, String> entry : textColumns.entrySet()) {
            if (all || entry.getKey().equals(search.field())) {
                parts.add(search.exact() ? entry.getValue() + " = :query" : entry.getValue() + " LIKE :pattern ESCAPE '!'");
                usesText = true;
            }
        }
        // 고른 항목이 이 조회에 없거나(예: ID 항목인데 숫자가 아닌 검색어) 맞는 컬럼이 없으면 아무것도 찾지 않는다.
        String sql = parts.isEmpty() ? " AND 1 = 0" : " AND (" + String.join(" OR ", parts) + ")";
        return new SearchClause(sql, search, usesQueryId, usesText && search.exact(), usesText && !search.exact());
    }

    static Map<String, String> columns(String... keyAndColumn) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int i = 0; i < keyAndColumn.length; i += 2) {
            columns.put(keyAndColumn[i], keyAndColumn[i + 1]);
        }
        return columns;
    }

    /** 검색 조건 SQL 조각과, 그 조각이 쓰는 파라미터만 바인딩하는 방법. */
    record SearchClause(String sql, AdminSearchConditionDto search, boolean usesQueryId, boolean usesQuery,
            boolean usesPattern) {

        static final SearchClause NONE = new SearchClause("", null, false, false, false);

        void bind(Query query) {
            if (usesQueryId) {
                query.setParameter("queryId", search.queryId());
            }
            if (usesQuery) {
                query.setParameter("query", search.query());
            }
            if (usesPattern) {
                query.setParameter("pattern", search.pattern());
            }
        }
    }
}
