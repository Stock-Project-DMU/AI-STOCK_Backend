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

import com.teamfp.aistock.domain.account.dto.request.ChargeBalanceRequest;
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

    @Mock
    private AccountTransactionService accountTransactionService;

    private AccountService accountService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 100L;
    private static final String STOCK_CODE = "005930";

    private Account account;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, userRepository, holdingValuationService, accountTransactionService);

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

    @Nested
    @DisplayName("계좌 개설")
    class CreateAccount {

        @Test
        @DisplayName("계좌를 개설하면 INITIAL_GRANT 원장 기록을 남긴다")
        void success_recordsInitialGrant() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account.getUser()));
            when(accountRepository.findAllByUserIdForUpdate(USER_ID)).thenReturn(List.of());

            accountService.createAccount(USER_ID, new com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest("계좌B"));

            org.mockito.Mockito.verify(accountTransactionService).record(
                    org.mockito.ArgumentMatchers.any(Account.class),
                    org.mockito.ArgumentMatchers.eq(com.teamfp.aistock.domain.account.entity.AccountTransactionType.INITIAL_GRANT),
                    org.mockito.ArgumentMatchers.eq(10_000_000L),
                    org.mockito.ArgumentMatchers.eq(0L),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("이미 계좌가 1개 있으면 ACCOUNT_LIMIT_EXCEEDED 예외를 던진다(유저당 계좌 1개)")
        void fail_accountLimitExceeded() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account.getUser()));
            when(accountRepository.findAllByUserIdForUpdate(USER_ID)).thenReturn(List.of(account));

            assertThatThrownBy(() -> accountService.createAccount(USER_ID,
                    new com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest("계좌B")))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("직접 충전")
    class ChargeBalance {

        @Test
        @DisplayName("입력한 금액만큼 충전하고 balanceBefore를 충전 전 값으로 담아 AUTO_CHARGE 원장 기록을 남긴다")
        void success_recordsAutoChargeWithBalanceBefore() {
            when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

            accountService.chargeBalance(USER_ID, ACCOUNT_ID, new ChargeBalanceRequest(3_500_000L));

            org.mockito.Mockito.verify(accountTransactionService).record(
                    org.mockito.ArgumentMatchers.eq(account),
                    org.mockito.ArgumentMatchers.eq(com.teamfp.aistock.domain.account.entity.AccountTransactionType.AUTO_CHARGE),
                    org.mockito.ArgumentMatchers.eq(3_500_000L),
                    org.mockito.ArgumentMatchers.eq(10_000_000L), // 충전 전 balance(=초기값)
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyString());
            assertThat(account.getBalance()).isEqualTo(13_500_000L);
            assertThat(account.getBaseBalance()).isEqualTo(13_500_000L);
            assertThat(account.getChargeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("직접 충전 3회를 모두 쓰면 CHARGE_LIMIT_EXCEEDED")
        void fail_whenChargeCountExhausted() {
            when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
            for (int i = 0; i < Account.MAX_CHARGE_COUNT; i++) {
                accountService.chargeBalance(USER_ID, ACCOUNT_ID, new ChargeBalanceRequest(1_000L));
            }

            assertThatThrownBy(() -> accountService.chargeBalance(USER_ID, ACCOUNT_ID, new ChargeBalanceRequest(1_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHARGE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("충전 후 예치금이 1조원을 넘으면 DEPOSIT_LIMIT_EXCEEDED")
        void fail_whenDepositLimitExceeded() {
            org.springframework.test.util.ReflectionTestUtils.setField(account, "balance", Account.MAX_DEPOSIT_AMOUNT - 50_000_000L);
            when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.chargeBalance(USER_ID, ACCOUNT_ID, new ChargeBalanceRequest(100_000_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
            assertThat(account.getChargeCount()).isZero();
        }

        @Test
        @DisplayName("예치금 한도 검사는 아주 큰 금액에서도 숫자가 넘치지 않고 거절한다")
        void canDeposit_rejectsHugeAmountWithoutOverflow() {
            assertThat(account.canDeposit(Long.MAX_VALUE)).isFalse();
            assertThat(account.canDeposit(Account.MAX_DEPOSIT_AMOUNT - account.getBalance())).isTrue();
            assertThat(account.canDeposit(Account.MAX_DEPOSIT_AMOUNT - account.getBalance() + 1)).isFalse();
        }

        @Test
        @DisplayName("정지된 계좌는 직접 충전할 수 없다")
        void fail_whenAccountSuspended() {
            account.suspend();
            when(accountRepository.findByAccountIdAndUserIdForUpdate(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.chargeBalance(USER_ID, ACCOUNT_ID, new ChargeBalanceRequest(1_000L)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED_CHARGE);
        }
    }

    @Nested
    @DisplayName("계좌번호 생성")
    class AccountNumber {

        @Test
        @DisplayName("계좌번호는 110으로 시작하는 숫자 12자리이고, 예치금 이자율은 연 0.50%로 고정된다")
        void accountNumberIs110PlusNineDigits() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account.getUser()));
            when(accountRepository.findAllByUserIdForUpdate(USER_ID)).thenReturn(List.of());
            when(accountRepository.findByAccountNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());

            var response = accountService.createAccount(USER_ID, new com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest("계좌B"));

            assertThat(response.accountNumber()).matches("110\\d{9}");
            assertThat(response.interestRate()).isEqualByComparingTo("0.50");
            assertThat(response.maxChargeCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("예치금 월 이자")
    class MonthlyInterest {

        private final java.time.LocalDateTime paidSince = java.time.LocalDateTime.of(2026, 10, 1, 0, 0);

        @Test
        @DisplayName("balance × 0.50% ÷ 12를 원 단위 미만 버림으로 지급하고, baseBalance도 같이 올려 수익률에 잡히지 않는다")
        void paysInterestOnBalanceOnly() {
            account.freezeForOrder(1_000_000L); // balance 9,000,000 / frozen 1,000,000 — 묶인 돈은 이자 대상 아님
            when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

            accountService.payMonthlyInterest(ACCOUNT_ID, paidSince, 9);

            // 9,000,000 × 0.50 / 1200 = 3,750
            assertThat(account.getBalance()).isEqualTo(9_003_750L);
            assertThat(account.getBaseBalance()).isEqualTo(10_003_750L);
            assertThat(account.getTotalInterest()).isEqualTo(3_750L);
            org.mockito.Mockito.verify(accountTransactionService).record(
                    org.mockito.ArgumentMatchers.eq(account),
                    org.mockito.ArgumentMatchers.eq(com.teamfp.aistock.domain.account.entity.AccountTransactionType.INTEREST),
                    org.mockito.ArgumentMatchers.eq(3_750L),
                    org.mockito.ArgumentMatchers.eq(9_000_000L),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("이번 달 이자가 이미 지급됐으면 다시 지급하지 않는다")
        void skipsWhenAlreadyPaidThisMonth() {
            when(accountRepository.findAccountWithUserByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(accountTransactionService.existsTransactionSince(ACCOUNT_ID,
                    com.teamfp.aistock.domain.account.entity.AccountTransactionType.INTEREST, paidSince)).thenReturn(true);

            accountService.payMonthlyInterest(ACCOUNT_ID, paidSince, 9);

            assertThat(account.getBalance()).isEqualTo(10_000_000L);
            org.mockito.Mockito.verify(accountTransactionService, org.mockito.Mockito.never()).record(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyLong(), anyLong(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }
    }
}
