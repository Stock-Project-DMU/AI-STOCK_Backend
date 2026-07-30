package com.teamfp.aistock.domain.account.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.account.dto.response.ProfitResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.dto.HoldingValuationDto;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * feature/mypage-profit — AccountService.getProfit() 단위 테스트.
 * 계좌 A/B/C는 서로 독립된 영역이라 항상 accountId 하나를 지정해 계산한다(합산 없음).
 *
 * 보유종목 조회 + 시세 평가는 HoldingValuationService로 위임하므로(코드리뷰 반영 — account
 * 도메인이 order 도메인의 Repository/Redis 서비스를 직접 참조하지 않도록), 여기서는
 * HoldingValuationService만 모킹하고 그 내부(Redis 배치 조회, 평단가 폴백) 검증은
 * HoldingValuationServiceTest에서 별도로 한다.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HoldingValuationService holdingValuationService;

    private AccountService accountService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 100L;
    private static final String STOCK_CODE = "005930";

    private Account account;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, userRepository, holdingValuationService);

        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        account = Account.builder()
                .user(user)
                .accountName("계좌A")
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(10_000_000L)
                .balance(10_000_000L)
                .build();
    }

    private Holding holdingOf(int quantity, long avgPrice) {
        return Holding.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .quantity(quantity)
                .avgPrice(avgPrice)
                .build();
    }

    @Nested
    @DisplayName("수익률 조회")
    class GetProfit {

        @Test
        @DisplayName("보유종목이 없으면 현금 잔고만으로 총 자산을 계산한다")
        void success_noHoldings() {
            when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
            when(holdingValuationService.getHoldingValuations(ACCOUNT_ID)).thenReturn(List.of());

            ProfitResponse response = accountService.getProfit(USER_ID, ACCOUNT_ID);

            assertThat(response.totalAsset()).isEqualTo(10_000_000L);
            assertThat(response.profitAmount()).isZero();
            assertThat(response.profitRate()).isZero();
        }

        @Test
        @DisplayName("보유종목 평가금액을 현재가 기준으로 합산한다")
        void success_withHoldings() {
            // 매수로 현금 100만원이 이미 나간 상태를 잔고에 직접 지정한다(잔고 900만원) — 이
            // 테스트는 수익률 계산만 검증하므로 Account.applyBuyOrder()를 거칠 이유가 없다.
            account = Account.builder()
                    .user(account.getUser())
                    .accountName("계좌A")
                    .accountNumber("ACC-0001")
                    .openedAt(LocalDate.now())
                    .baseBalance(10_000_000L)
                    .balance(9_000_000L)
                    .build();
            Holding holding = holdingOf(10, 100_000L); // 10주, 평단가 10만원

            when(accountRepository.findByAccountIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
            when(holdingValuationService.getHoldingValuations(ACCOUNT_ID))
                    .thenReturn(List.of(HoldingValuationDto.of(holding, 120_000L)));

            ProfitResponse response = accountService.getProfit(USER_ID, ACCOUNT_ID);

            // 현금 9,000,000 + 주식평가(10주*120,000=1,200,000) = 10,200,000
            assertThat(response.totalAsset()).isEqualTo(10_200_000L);
            assertThat(response.profitAmount()).isEqualTo(200_000L);
            assertThat(response.profitRate()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("내 계좌가 아니면 ACCOUNT_NOT_FOUND 예외를 던진다")
        void fail_accountNotFound() {
            when(accountRepository.findByAccountIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getProfit(USER_ID, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }
}
