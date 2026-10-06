package com.teamfp.aistock.domain.admin.dto.response;

/**
 * 관리자 "전체 활동 기록"(GET /api/admin/activities)의 기록 종류(feat/admin-improvements). 한 가지 일은 한 줄로
 * 보여준다 — 예를 들어 충전 요청은 요청·승인/반려·입금이 CHARGE_REQUEST 한 줄에, 주문은 주문·체결·관리자 강제 취소가
 * TRADE 한 줄에 담긴다. 관리자가 한 처리는 따로 모으지 않고 그 일이 속한 이력 안에 결과로 들어간다.
 * 화면 탭(AdminActivityCategory)과의 대응은 category()로 정한다.
 */
public enum AdminActivityType {
    // 회원이력
    SIGNUP(AdminActivityCategory.MEMBER),
    WITHDRAWAL(AdminActivityCategory.MEMBER),
    USER_STATUS(AdminActivityCategory.MEMBER),
    // 계좌는 회원당 1개라 계좌(거래) 정지·해제도 회원이력에 둔다(문구로 회원 정지와 구분).
    ACCOUNT_STATUS(AdminActivityCategory.MEMBER),
    ADMIN_CREATE(AdminActivityCategory.MEMBER),
    // 거래이력
    TRADE(AdminActivityCategory.TRADE),
    // 충전차감이력
    CHARGE_REQUEST(AdminActivityCategory.CHARGE),
    SELF_BALANCE(AdminActivityCategory.CHARGE),
    ADMIN_BALANCE(AdminActivityCategory.CHARGE),
    // 문의이력 — 문의 등록과 답변이 한 줄
    INQUIRY(AdminActivityCategory.INQUIRY);

    private final AdminActivityCategory category;

    AdminActivityType(AdminActivityCategory category) {
        this.category = category;
    }

    public AdminActivityCategory category() {
        return category;
    }
}
