package com.teamfp.aistock.domain.admin.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeHistorySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.response.AdminChargeHistoryEntryType;
import com.teamfp.aistock.domain.admin.repository.AdminNativeSearchSupport.SearchClause;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * 관리자 "가상계좌 관리 → 충전·차감 이력" 조회(feat/admin-improvements). 처리가 끝난 충전·차감을 한 목록으로 보여주기
 * 위해 잔고 내역(account_transactions의 충전·차감 4종)과 거절된 충전 요청(charge_requests.status = 'REJECTED')을
 * UNION ALL로 합쳐, 요청한 페이지의 (종류, ID)만 꺼낸다. 승인된 요청은 잔고 내역(ADMIN_CHARGE,
 * related_charge_request_id)으로 이미 들어 있어 따로 합치지 않는다. 상세는 AdminAccountTransactionService가 채운다.
 *
 * 거절 건의 시각은 처리 시각(decided_at), 금액 정렬은 잔고 내역이면 증감액의 절댓값, 거절 건이면 요청 금액 기준이다.
 * 검색은 다른 관리자 목록과 같은 규칙(AdminNativeSearchSupport)이고, 거절 건에는 내역 번호가 없어 TRANSACTION_ID
 * 검색에는 걸리지 않는다.
 */
@Repository
public class AdminChargeHistoryRepository {

    // 유형 필터용 — AdminAccountTransactionService.CHARGE_DEDUCTION_TYPES 중 고른 것만 넘어온다(enum 이름이라 SQL에 바로 넣어도 안전하다).
    private static final String TRANSACTION_SQL =
            "SELECT 'TRANSACTION' AS entry_type, t.transaction_id AS id, t.created_at AS occurred_at, "
                    + "ABS(t.amount) AS sort_amount, t.type AS type_name, t.transaction_id AS transaction_id, u.login_id AS login_id, "
                    + "u.name AS name, a.account_number AS account_number, t.balance_before AS balance_before, "
                    + "t.balance_after AS balance_after, t.related_charge_request_id AS charge_request_id, "
                    + "p.login_id AS processor_login_id, t.reason AS reason_text "
                    + "FROM account_transactions t JOIN accounts a ON a.account_id = t.account_id "
                    + "JOIN users u ON u.user_id = a.user_id LEFT JOIN users p ON p.user_id = t.processed_by "
                    + "WHERE t.type IN (%s)";
    private static final String REJECTED_SQL =
            "SELECT 'REJECTED_REQUEST' AS entry_type, c.request_id AS id, COALESCE(c.decided_at, c.requested_at) AS occurred_at, "
                    + "c.amount AS sort_amount, 'REJECTED' AS type_name, NULL AS transaction_id, u.login_id AS login_id, u.name AS name, "
                    + "a.account_number AS account_number, NULL AS balance_before, NULL AS balance_after, "
                    + "c.request_id AS charge_request_id, d.login_id AS processor_login_id, c.decision_reason AS reason_text "
                    + "FROM charge_requests c JOIN accounts a ON a.account_id = c.account_id "
                    + "JOIN users u ON u.user_id = a.user_id LEFT JOIN users d ON d.user_id = c.decided_by "
                    + "WHERE c.status = 'REJECTED'";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param transactionTypes 포함할 잔고 내역 유형(비어 있으면 잔고 내역은 빼고 거절 건만)
     * @param includeRejected  거절된 충전 요청을 포함할지
     */
    public List<HistoryKey> findKeys(List<AccountTransactionType> transactionTypes, boolean includeRejected,
            AdminSearchConditionDto search, AdminSortType sortType, AdminChargeHistorySortColumn sortColumn,
            Sort.Direction direction, long offset, int limit) {
        SearchClause clause = searchClause(search);
        String sql = "SELECT entry_type, id FROM (" + unionSql(transactionTypes, includeRejected) + ") h WHERE 1 = 1"
                + clause.sql() + " ORDER BY " + orderBy(sortType, sortColumn, direction) + " LIMIT :limit OFFSET :offset";
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("limit", limit)
                .setParameter("offset", offset);
        clause.bind(query);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new HistoryKey(AdminChargeHistoryEntryType.valueOf((String) row[0]), ((Number) row[1]).longValue()))
                .toList();
    }

    public long count(List<AccountTransactionType> transactionTypes, boolean includeRejected, AdminSearchConditionDto search) {
        SearchClause clause = searchClause(search);
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + unionSql(transactionTypes, includeRejected)
                + ") h WHERE 1 = 1" + clause.sql());
        clause.bind(query);
        return ((Number) query.getSingleResult()).longValue();
    }

    private SearchClause searchClause(AdminSearchConditionDto search) {
        return AdminNativeSearchSupport.clause(search,
                AdminNativeSearchSupport.columns("LOGIN_ID", "h.login_id", "NAME", "h.name",
                        "ACCOUNT_NUMBER", "h.account_number"),
                AdminNativeSearchSupport.columns("TRANSACTION_ID", "h.transaction_id"));
    }

    private String unionSql(List<AccountTransactionType> transactionTypes, boolean includeRejected) {
        List<String> parts = new ArrayList<>();
        if (!transactionTypes.isEmpty()) {
            String types = transactionTypes.stream().map(type -> "'" + type.name() + "'").collect(Collectors.joining(", "));
            parts.add(String.format(TRANSACTION_SQL, types));
        }
        if (includeRejected) {
            parts.add(REJECTED_SQL);
        }
        return String.join(" UNION ALL ", parts);
    }

    // 열 제목 정렬(sortColumn)이 있으면 그 열 + 방향이 우선, 없으면 정렬 선택칸(sortType). 값이 같으면 시각·ID 내림차순.
    private String orderBy(AdminSortType sortType, AdminChargeHistorySortColumn sortColumn, Sort.Direction direction) {
        if (sortColumn != null) {
            String dir = direction == Sort.Direction.ASC ? "ASC" : "DESC";
            String column = switch (sortColumn) {
                case TRANSACTION_ID -> "transaction_id";
                case ACCOUNT_NUMBER -> "account_number";
                case USER -> "name";
                case TYPE -> "type_name";
                case AMOUNT -> "sort_amount";
                case BALANCE_BEFORE -> "balance_before";
                case BALANCE_AFTER -> "balance_after";
                case CHARGE_REQUEST_ID -> "charge_request_id";
                case PROCESSED_BY -> "processor_login_id";
                case REASON -> "reason_text";
                case OCCURRED_AT -> "occurred_at";
            };
            return column + " " + dir + ", occurred_at DESC, entry_type DESC, id DESC";
        }
        return switch (sortType == null ? AdminSortType.LATEST : sortType) {
            case LATEST -> "occurred_at DESC, entry_type DESC, id DESC";
            case OLDEST -> "occurred_at ASC, entry_type ASC, id ASC";
            case AMOUNT_DESC -> "sort_amount DESC, occurred_at DESC, id DESC";
            case AMOUNT_ASC -> "sort_amount ASC, occurred_at DESC, id DESC";
        };
    }

    public record HistoryKey(AdminChargeHistoryEntryType entryType, Long id) {
    }
}
