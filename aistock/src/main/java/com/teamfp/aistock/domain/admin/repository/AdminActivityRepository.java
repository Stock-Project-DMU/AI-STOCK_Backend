package com.teamfp.aistock.domain.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.teamfp.aistock.domain.admin.dto.request.AdminSearchConditionDto;
import com.teamfp.aistock.domain.admin.dto.response.AdminActivityType;
import com.teamfp.aistock.domain.admin.repository.AdminNativeSearchSupport.SearchClause;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * 관리자 "전체 활동 기록"용 조회(feat/admin-improvements). 회원·거래·충전차감·문의 이력을 이루는 테이블들을 UNION ALL로
 * 합쳐 발생 시각 내림차순으로 정렬한 뒤, 요청한 페이지의 (종류, ID, 시각, 대상 회원)만 꺼낸다. 상세는
 * AdminActivityService가 꺼낸 ID로 종류별로 한 번에 다시 조회한다.
 *
 * 한 가지 일은 한 줄이라 시작 시점의 테이블 한 곳에서만 가져온다 — 충전 요청은 charge_requests(요청 시각)에서만, 그
 * 승인 입금(account_transactions)과 승인·반려 감사 로그는 따로 합치지 않는다. 주문 강제 취소 감사 로그도 주문 한 줄에
 * 들어가므로 합치지 않는다. 관리자 지급·차감은 충전 요청과 무관한 잔고 내역(related_charge_request_id IS NULL)만 쓴다.
 *
 * 모든 갈래가 대상 회원(user_id·login_id·name·email)을 같이 꺼내 바깥 쿼리에서 회원 검색·기간 조건을 한 번에 건다.
 * JPQL은 서로 다른 엔티티의 UNION을 지원하지 않아 네이티브 쿼리를 쓴다.
 */
@Repository
public class AdminActivityRepository {

    private static final String USER_COLUMNS = "u.user_id AS user_id, u.login_id AS login_id, u.name AS name, u.email AS email";

    @PersistenceContext
    private EntityManager entityManager;

    /** types에 담긴 종류만 합친다. 같은 시각이면 종류·ID 내림차순으로 순서를 고정해 페이지를 넘겨도 중복·누락이 없다. */
    public List<ActivityKey> findKeys(List<AdminActivityType> types, AdminSearchConditionDto search, LocalDateTime from,
            LocalDateTime toExclusive, long offset, int limit) {
        SearchClause clause = searchClause(search);
        String sql = "SELECT activity_type, id, occurred_at, user_id, login_id, name FROM (" + unionSql(types)
                + ") activities WHERE 1 = 1" + clause.sql() + periodSql(from, toExclusive)
                + " ORDER BY occurred_at DESC, activity_type DESC, id DESC LIMIT :limit OFFSET :offset";
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("limit", limit)
                .setParameter("offset", offset);
        clause.bind(query);
        bindPeriod(query, from, toExclusive);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new ActivityKey(
                        AdminActivityType.valueOf((String) row[0]),
                        ((Number) row[1]).longValue(),
                        toLocalDateTime(row[2]),
                        row[3] == null ? null : ((Number) row[3]).longValue(),
                        (String) row[4],
                        (String) row[5]))
                .toList();
    }

    public long count(List<AdminActivityType> types, AdminSearchConditionDto search, LocalDateTime from,
            LocalDateTime toExclusive) {
        SearchClause clause = searchClause(search);
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + unionSql(types) + ") activities WHERE 1 = 1"
                + clause.sql() + periodSql(from, toExclusive));
        clause.bind(query);
        bindPeriod(query, from, toExclusive);
        return ((Number) query.getSingleResult()).longValue();
    }

    private SearchClause searchClause(AdminSearchConditionDto search) {
        return AdminNativeSearchSupport.clause(search,
                AdminNativeSearchSupport.columns("LOGIN_ID", "activities.login_id", "NAME", "activities.name",
                        "EMAIL", "activities.email"),
                AdminNativeSearchSupport.columns("USER_ID", "activities.user_id"));
    }

    private String periodSql(LocalDateTime from, LocalDateTime toExclusive) {
        return (from == null ? "" : " AND activities.occurred_at >= :fromAt")
                + (toExclusive == null ? "" : " AND activities.occurred_at < :toAt");
    }

    private void bindPeriod(Query query, LocalDateTime from, LocalDateTime toExclusive) {
        if (from != null) {
            query.setParameter("fromAt", from);
        }
        if (toExclusive != null) {
            query.setParameter("toAt", toExclusive);
        }
    }

    private String unionSql(List<AdminActivityType> types) {
        return types.stream().map(this::sqlOf).collect(Collectors.joining(" UNION ALL "));
    }

    private String sqlOf(AdminActivityType type) {
        return switch (type) {
            // 가입은 탈퇴하지 않은 회원만(관리자 회원 목록과 같은 기준), 탈퇴는 탈퇴 처리된 회원의 탈퇴 시각
            case SIGNUP -> "SELECT 'SIGNUP' AS activity_type, u.user_id AS id, u.created_at AS occurred_at, " + USER_COLUMNS
                    + " FROM users u WHERE u.is_active = 1";
            case WITHDRAWAL -> "SELECT 'WITHDRAWAL' AS activity_type, u.user_id AS id, u.deleted_at AS occurred_at, "
                    + USER_COLUMNS + " FROM users u WHERE u.is_active = 0 AND u.deleted_at IS NOT NULL";
            case USER_STATUS -> "SELECT 'USER_STATUS' AS activity_type, l.audit_log_id AS id, l.created_at AS occurred_at, "
                    + USER_COLUMNS + " FROM audit_logs l JOIN users u ON u.user_id = l.target_id "
                    + "WHERE l.action = 'USER_STATUS_CHANGE' AND l.target_type = 'USER'";
            case ACCOUNT_STATUS -> "SELECT 'ACCOUNT_STATUS' AS activity_type, l.audit_log_id AS id, l.created_at AS occurred_at, "
                    + USER_COLUMNS + " FROM audit_logs l JOIN accounts a ON a.account_id = l.target_id "
                    + "JOIN users u ON u.user_id = a.user_id "
                    + "WHERE l.action = 'ACCOUNT_STATUS_CHANGE' AND l.target_type = 'ACCOUNT'";
            case ADMIN_CREATE -> "SELECT 'ADMIN_CREATE' AS activity_type, l.audit_log_id AS id, l.created_at AS occurred_at, "
                    + USER_COLUMNS + " FROM audit_logs l JOIN users u ON u.user_id = l.target_id "
                    + "WHERE l.action = 'ADMIN_CREATE'";
            case TRADE -> "SELECT 'TRADE' AS activity_type, o.order_id AS id, o.ordered_at AS occurred_at, " + USER_COLUMNS
                    + " FROM orders o JOIN accounts a ON a.account_id = o.account_id JOIN users u ON u.user_id = a.user_id";
            case CHARGE_REQUEST -> "SELECT 'CHARGE_REQUEST' AS activity_type, c.request_id AS id, c.requested_at AS occurred_at, "
                    + USER_COLUMNS + " FROM charge_requests c JOIN accounts a ON a.account_id = c.account_id "
                    + "JOIN users u ON u.user_id = a.user_id";
            case SELF_BALANCE -> "SELECT 'SELF_BALANCE' AS activity_type, t.transaction_id AS id, t.created_at AS occurred_at, "
                    + USER_COLUMNS + " FROM account_transactions t JOIN accounts a ON a.account_id = t.account_id "
                    + "JOIN users u ON u.user_id = a.user_id WHERE t.type IN ('AUTO_CHARGE', 'AUTO_DEDUCTION')";
            case ADMIN_BALANCE -> "SELECT 'ADMIN_BALANCE' AS activity_type, t.transaction_id AS id, t.created_at AS occurred_at, "
                    + USER_COLUMNS + " FROM account_transactions t JOIN accounts a ON a.account_id = t.account_id "
                    + "JOIN users u ON u.user_id = a.user_id "
                    + "WHERE t.type IN ('ADMIN_CHARGE', 'ADMIN_DEDUCTION') AND t.related_charge_request_id IS NULL";
            case INQUIRY -> "SELECT 'INQUIRY' AS activity_type, i.inquiry_id AS id, i.created_at AS occurred_at, " + USER_COLUMNS
                    + " FROM inquiries i JOIN users u ON u.user_id = i.user_id";
        };
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value == null ? null : LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    /** 페이지에 나온 활동 한 줄의 키 — 공통 필드(시각·대상 회원)는 여기서 바로 채우고, 상세만 따로 조회한다. */
    public record ActivityKey(AdminActivityType type, Long id, LocalDateTime occurredAt, Long userId, String loginId,
            String userName) {
    }
}
