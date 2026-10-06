package com.teamfp.aistock.domain.admin.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.JpaSort;

import com.teamfp.aistock.domain.admin.dto.request.AdminAccountSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminAccountSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminChargeRequestSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminInquirySortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminNoticeSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminSortType;
import com.teamfp.aistock.domain.admin.dto.request.AdminTradeSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSortColumn;
import com.teamfp.aistock.domain.admin.dto.request.AdminUserSortType;

/**
 * 관리자 목록 정렬 → 페이지 정렬(feat/admin-improvements). 두 가지 입력을 받는다.
 * - sortBy(정렬 선택칸): 최신순·금액순 같은 미리 정한 정렬
 * - sortColumn + direction(표 열 제목 클릭): 그 열 기준 오름/내림차순 — 값이 있으면 sortBy보다 우선한다
 * 클라이언트가 보내는 임의의 sort 파라미터(없는 필드면 서버 오류가 나던 것)는 쓰지 않고 페이지 번호·크기만 가져간다.
 * 같은 값끼리는 ID 내림차순을 2차 기준으로 둬서 페이지를 넘겨도 순서가 흔들리지 않는다.
 */
final class AdminSortSupport {

    // 거래 금액(주문가 × 수량) — 엔티티 필드가 아니라 식이라 JpaSort.unsafe로 쿼리에 그대로 넣는다.
    private static final String TRADE_AMOUNT = "(o.orderPrice * o.quantity)";
    // 문의 답변 대기 먼저 — 대기(PENDING)면 0, 아니면 1. AdminInquiryService 검색 쿼리의 별칭 i 기준
    private static final String INQUIRY_PENDING_FIRST =
            "(case when i.status = com.teamfp.aistock.domain.inquiry.entity.InquiryStatus.PENDING then 0 else 1 end)";

    private AdminSortSupport() {
    }

    // 회원·관리자 목록: 최신 가입순 / 오래된 가입순 / 이름순, 또는 열 제목 정렬
    static Pageable users(Pageable pageable, AdminUserSortType sortType, AdminUserSortColumn column, Direction direction) {
        if (column != null) {
            String property = switch (column) {
                case USER_ID -> "userId";
                case LOGIN_ID -> "loginId";
                case NAME -> "name";
                case EMAIL -> "email";
                case CREATED_AT -> "createdAt";
                case STATUS -> "status";
            };
            return page(pageable, Sort.by(new Sort.Order(direction(direction), property), Sort.Order.desc("userId")));
        }
        Sort sort = switch (sortType == null ? AdminUserSortType.LATEST : sortType) {
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("userId"));
            case OLDEST -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("userId"));
            case NAME -> Sort.by(Sort.Order.asc("name"), Sort.Order.desc("userId"));
        };
        return page(pageable, sort);
    }

    // 거래 목록: 최신순 / 오래된순 / 주문 금액(주문가 × 수량) 큰순·작은순, 또는 열 제목 정렬
    static Pageable trades(Pageable pageable, AdminSortType sortType, AdminTradeSortColumn column, Direction direction) {
        if (column != null) {
            Direction dir = direction(direction);
            Sort sort = column == AdminTradeSortColumn.AMOUNT
                    ? JpaSort.unsafe(dir, TRADE_AMOUNT)
                    : Sort.by(new Sort.Order(dir, switch (column) {
                        case ORDER_ID -> "orderId";
                        case USER -> "account.user.name";
                        case STOCK -> "stockName";
                        case ORDER_TYPE -> "orderType";
                        case QUANTITY -> "quantity";
                        case STATUS -> "status";
                        case ORDERED_AT -> "orderedAt";
                        case AMOUNT -> throw new IllegalStateException();
                    }));
            return page(pageable, sort.and(Sort.by(Sort.Order.desc("orderId"))));
        }
        Sort sort = switch (sortType == null ? AdminSortType.LATEST : sortType) {
            case LATEST -> Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("orderId"));
            case OLDEST -> Sort.by(Sort.Order.asc("orderedAt"), Sort.Order.asc("orderId"));
            case AMOUNT_DESC -> JpaSort.unsafe(Direction.DESC, TRADE_AMOUNT).and(Sort.by(Sort.Order.desc("orderId")));
            case AMOUNT_ASC -> JpaSort.unsafe(Direction.ASC, TRADE_AMOUNT).and(Sort.by(Sort.Order.desc("orderId")));
        };
        return page(pageable, sort);
    }

    // 충전 요청 목록: 최신순 / 오래된순 / 요청 금액 큰순·작은순, 또는 열 제목 정렬
    static Pageable chargeRequests(Pageable pageable, AdminSortType sortType, AdminChargeRequestSortColumn column,
            Direction direction) {
        if (column != null) {
            String property = switch (column) {
                case REQUEST_ID -> "requestId";
                case USER -> "account.user.name";
                case ACCOUNT_NUMBER -> "account.accountNumber";
                case AMOUNT -> "amount";
                case REQUESTED_AT -> "requestedAt";
                case STATUS -> "status";
            };
            return page(pageable, Sort.by(new Sort.Order(direction(direction), property), Sort.Order.desc("requestId")));
        }
        Sort sort = switch (sortType == null ? AdminSortType.LATEST : sortType) {
            case LATEST -> Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("requestId"));
            case OLDEST -> Sort.by(Sort.Order.asc("requestedAt"), Sort.Order.asc("requestId"));
            case AMOUNT_DESC -> Sort.by(Sort.Order.desc("amount"), Sort.Order.desc("requestId"));
            case AMOUNT_ASC -> Sort.by(Sort.Order.asc("amount"), Sort.Order.desc("requestId"));
        };
        return page(pageable, sort);
    }

    // 계좌 목록: 최신 개설순 / 잔고 많은순 / 잔고 적은순, 또는 열 제목 정렬
    static Pageable accounts(Pageable pageable, AdminAccountSortType sortType, AdminAccountSortColumn column,
            Direction direction) {
        if (column != null) {
            String property = switch (column) {
                case ACCOUNT_NUMBER -> "accountNumber";
                case USER -> "user.name";
                case BALANCE -> "balance";
                case OPENED_AT -> "openedAt";
                case STATUS -> "status";
            };
            return page(pageable, Sort.by(new Sort.Order(direction(direction), property), Sort.Order.desc("accountId")));
        }
        Sort sort = switch (sortType == null ? AdminAccountSortType.LATEST : sortType) {
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("accountId"));
            case BALANCE_DESC -> Sort.by(Sort.Order.desc("balance"), Sort.Order.desc("accountId"));
            case BALANCE_ASC -> Sort.by(Sort.Order.asc("balance"), Sort.Order.desc("accountId"));
        };
        return page(pageable, sort);
    }

    // 문의 목록: 답변 대기 먼저(기본) / 최신순 / 오래된순, 또는 열 제목 정렬. 답변 대기 먼저는 status 정렬에 기대지
    // 않고 "대기면 0, 아니면 1"을 직접 계산해 정렬한다 — MySQL ENUM 칸은 글자 순이 아니라 정의 순서로 정렬되는데,
    // 운영(schema.sql: 'PENDING','ANSWERED')과 로컬(Hibernate 자동 생성: 'ANSWERED','PENDING')의 정의 순서가 달라
    // status 내림차순이 운영에서는 답변 완료를 먼저 보여줬다.
    static Pageable inquiries(Pageable pageable, AdminInquirySortType sortType, AdminInquirySortColumn column,
            Direction direction) {
        if (column != null) {
            String property = switch (column) {
                case INQUIRY_ID -> "inquiryId";
                case USER -> "user.name";
                case TITLE -> "title";
                case STATUS -> "status";
                case CREATED_AT -> "createdAt";
                case ANSWERED_AT -> "answeredAt";
            };
            return page(pageable, Sort.by(new Sort.Order(direction(direction), property), Sort.Order.desc("inquiryId")));
        }
        Sort sort = switch (sortType == null ? AdminInquirySortType.PENDING_FIRST : sortType) {
            case PENDING_FIRST -> JpaSort.unsafe(Direction.ASC, INQUIRY_PENDING_FIRST)
                    .and(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("inquiryId")));
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("inquiryId"));
            case OLDEST -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("inquiryId"));
        };
        return page(pageable, sort);
    }

    // 알림 관리(보낸 공지): 최신 발송순(기본) / 오래된 발송순, 또는 열 제목 정렬
    static Pageable notices(Pageable pageable, AdminNoticeSortType sortType, AdminNoticeSortColumn column,
            Direction direction) {
        if (column != null) {
            String property = switch (column) {
                case NOTICE_ID -> "noticeId";
                case TITLE -> "title";
                case TYPE -> "type";
                case TARGET_TYPE -> "targetType";
                case SENT_COUNT -> "sentCount";
                case POPUP_END_DATE -> "popupEndDate";
                case CREATED_BY -> "createdByLoginId";
                case CREATED_AT -> "createdAt";
            };
            return page(pageable, Sort.by(new Sort.Order(direction(direction), property), Sort.Order.desc("noticeId")));
        }
        Sort sort = switch (sortType == null ? AdminNoticeSortType.LATEST : sortType) {
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("noticeId"));
            case OLDEST -> Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("noticeId"));
        };
        return page(pageable, sort);
    }

    private static Direction direction(Direction direction) {
        return direction == null ? Direction.DESC : direction;
    }

    private static Pageable page(Pageable pageable, Sort sort) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
