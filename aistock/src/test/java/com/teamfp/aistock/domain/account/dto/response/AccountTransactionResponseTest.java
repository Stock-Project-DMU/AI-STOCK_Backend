package com.teamfp.aistock.domain.account.dto.response;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

/** 계좌 내역 응답 — 사용자 조회는 관리자 지급·차감 사유를 고정 문구로, 관리자 조회는 원래 사유로 보여준다(2026-10-13). */
class AccountTransactionResponseTest {

    private AccountTransaction transaction(AccountTransactionType type, String reason) {
        User user = User.builder().loginId("tester").name("테스터").role(Role.USER).isActive(true).build();
        Account account = Account.builder().user(user).accountName("계좌A").accountNumber("ACC-10")
                .openedAt(LocalDate.now()).baseBalance(1_000_000L).balance(1_000_000L).build();
        ReflectionTestUtils.setField(account, "accountId", 10L);
        return AccountTransaction.builder().account(account).type(type).amount(50_000L)
                .balanceBefore(1_000_000L).balanceAfter(1_050_000L).processedBy(1L).reason(reason).build();
    }

    @Test
    @DisplayName("사용자 조회: 관리자 지급은 '관리자 지급', 관리자 차감은 '관리자 차감'으로 바꾸고 관리자 메모는 보이지 않는다")
    void forUser_masksAdminReasons() {
        assertThat(AccountTransactionResponse.forUser(transaction(AccountTransactionType.ADMIN_CHARGE, "이벤트 보상 - 내부 메모")).reason())
                .isEqualTo("관리자 지급");
        assertThat(AccountTransactionResponse.forUser(transaction(AccountTransactionType.ADMIN_DEDUCTION, "부정 사용 회수")).reason())
                .isEqualTo("관리자 차감");
    }

    @Test
    @DisplayName("사용자 조회: 관리자 지급·차감이 아닌 내역은 원래 사유를 그대로 둔다")
    void forUser_keepsOtherReasons() {
        assertThat(AccountTransactionResponse.forUser(transaction(AccountTransactionType.AUTO_CHARGE, "직접 충전")).reason())
                .isEqualTo("직접 충전");
    }

    @Test
    @DisplayName("관리자 조회(from): 관리자가 적은 원래 사유를 그대로 보여준다")
    void from_keepsAdminReason() {
        assertThat(AccountTransactionResponse.from(transaction(AccountTransactionType.ADMIN_CHARGE, "이벤트 보상 - 내부 메모")).reason())
                .isEqualTo("이벤트 보상 - 내부 메모");
    }
}
