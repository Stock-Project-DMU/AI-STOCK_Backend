package com.teamfp.aistock.domain.account.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.request.ChargeBalanceRequest;
import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.dto.response.ProfitResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountStatus;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.order.service.HoldingValuationService;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    // 계좌 개설 시 지급되는 고정 가상캐시. 회원가입 직후 첫 계좌든 이후 유저가 자유롭게
    // 추가하는 계좌든 항상 이 금액으로 시작한다(schema.sql accounts.balance/base_balance
    // DEFAULT와 동일한 값).
    private static final long INITIAL_BALANCE = 10_000_000L;
    // 계좌번호 = 고정 은행코드 "110" + 랜덤 9자리 숫자(총 12자리, 숫자만 저장 — 하이픈은 화면에서만 붙인다).
    private static final String ACCOUNT_NUMBER_PREFIX = "110";
    private static final int ACCOUNT_NUMBER_RANDOM_BOUND = 1_000_000_000;
    // 9자리 앞자리 0 채우기용 — String.format("%09d")는 JVM 기본 로케일에 따라 비ASCII 숫자가 나올 수
    // 있어(코드리뷰 반영), 10억을 더한 뒤 맨 앞 "1"을 떼는 방식으로 항상 ASCII 숫자 9자리를 만든다.
    private static final long ACCOUNT_NUMBER_PAD_OFFSET = 1_000_000_000L;
    // 유저 1명이 만들 수 있는 최대 계좌 수. 원래 성향별로 나눠 투자하도록 3개였으나, 목표 도달
    // 시뮬레이션이 "내 보유종목 + 예수금"을 계좌 하나로 특정해야 해서 1개로 줄였다(2026-10-01,
    // accounts.uq_account_user — account_single_migration.sql 참고).
    private static final int MAX_ACCOUNT_COUNT = 1;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    // 수익률 계산에 필요한 보유종목+시세 평가는 order 도메인의 HoldingRepository/
    // RedisStockCacheService를 여기서 직접 주입받지 않고, 그 도메인의 서비스인
    // HoldingValuationService를 통해서만 접근한다(코드리뷰 반영 — 이전에는 Repository를
    // 직접 참조해 도메인 경계를 넘었었다).
    private final HoldingValuationService holdingValuationService;
    // 잔고 변동 원장 기록(ADMIN_API_BACKEND_HANDOFF.md 4.3) — 계좌 개설(INITIAL_GRANT)/자동
    // 충전(AUTO_CHARGE) 시점에 record()를 호출한다.
    private final AccountTransactionService accountTransactionService;

    @Transactional(readOnly = true)
    public List<AccountInfoResponse> getMyAccounts(Long userId) {
        return accountRepository.findAllByUserId(userId).stream()
                .map(AccountInfoResponse::from)
                .toList();
    }

    /**
     * 계좌 개설. 회원가입 시 자동 생성되는 첫 계좌도, 이후 유저가 추가하는 계좌도 전부 이
     * 메서드를 거친다(feature/auth-signup이 구현되면 그대로 재사용할 수 있도록). 유저 1명당
     * 최대 1개(MAX_ACCOUNT_COUNT)까지만 허용한다.
     *
     * accounts.user_id에는 유니크 제약이 없어(1:N) "개수 확인 → 저장(save)" 사이의 경합을 DB가
     * 대신 막아주지 않는다. 그래서 accountRepository.findAllByUserIdForUpdate로 같은 유저의
     * Account 행(0개여도 idx_account_user 인덱스로 갭 락이 걸림)에 비관적 락을 걸어 개수를 확인한다
     * — User 행 전체를 잠그면 로그인·관리자 정지처럼 계좌와 무관한 다른 기능까지 이 락을
     * 기다리게 될 수 있어, Account 쪽만 좁게 잠그는 방식으로 바꿨다(코드 리뷰 반영). 락 겸 개수
     * 확인을 한 조회로 처리하므로 별도 countByUserId 호출은 필요 없다.
     */
    @Transactional
    public AccountInfoResponse createAccount(Long userId, CreateAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (accountRepository.findAllByUserIdForUpdate(userId).size() >= MAX_ACCOUNT_COUNT) {
            throw new CustomException(ErrorCode.ACCOUNT_LIMIT_EXCEEDED);
        }

        Account account = Account.builder()
                .user(user)
                .accountName(request.accountName())
                .accountNumber(generateAccountNumber())
                .openedAt(LocalDate.now())
                .baseBalance(INITIAL_BALANCE)
                .balance(INITIAL_BALANCE)
                .build();
        accountRepository.save(account);
        accountTransactionService.record(account, AccountTransactionType.INITIAL_GRANT, INITIAL_BALANCE, 0L,
                null, null, null, "계좌 개설 초기 지급");

        return AccountInfoResponse.from(account);
    }

    /**
     * 사용자 직접 충전. 금액은 사용자가 자유롭게 입력하고, 계좌당 Account.MAX_CHARGE_COUNT(3)회까지
     * 관리자 승인 없이 바로 balance에 반영된다(연속으로 3번 다 써도 무방). chargeCount가 한도에
     * 도달하면 CHARGE_LIMIT_EXCEEDED를 던지고, 이후에는 충전 요청(ChargeRequestService)으로
     * 관리자 승인을 받아야 한다. 정지(SUSPENDED) 계좌는 충전 요청과 동일하게 막는다.
     *
     * 계좌를 findByAccountIdAndUserIdForUpdate로 비관적 락을 걸어 조회한다 — Account.version
     * (낙관적 락)만으로는 chargeCount==2인 상태에서 동시에 두 번째 충전 요청이 들어왔을 때,
     * 순서대로 처리했다면 정상 처리됐을 요청까지 OPTIMISTIC_LOCK_CONFLICT(409)로 실패해버린다.
     * 비관적 락으로 두 요청을 순서대로 처리하면, 나중 요청은 먼저 커밋된 chargeCount를 다시 보고
     * 정확히 CHARGE_LIMIT_EXCEEDED 여부를 판단하게 된다.
     */
    @Transactional
    public AccountInfoResponse chargeBalance(Long userId, Long accountId, ChargeBalanceRequest request) {
        Account account = accountRepository.findByAccountIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new CustomException(ErrorCode.ACCOUNT_SUSPENDED_CHARGE);
        }
        if (!account.hasRemainingChargeCount()) {
            throw new CustomException(ErrorCode.CHARGE_LIMIT_EXCEEDED);
        }
        if (!account.canDeposit(request.amount())) {
            throw new CustomException(ErrorCode.DEPOSIT_LIMIT_EXCEEDED);
        }
        long balanceBefore = account.getBalance();
        account.chargeBalance(request.amount());
        accountTransactionService.record(account, AccountTransactionType.AUTO_CHARGE, request.amount(), balanceBefore,
                null, null, null, "직접 충전");
        return AccountInfoResponse.from(account);
    }

    /**
     * 계좌번호 생성 — "110" + 랜덤 9자리(앞자리 0 허용)의 12자리 숫자. 화면에서는 110-123-456789처럼
     * 하이픈을 붙여 보여준다. accounts.account_number에 UNIQUE 제약이 있지만, 저장 시점에
     * 충돌로 실패하지 않도록 생성 단계에서 이미 쓰인 번호면 다시 뽑는다.
     */
    private String generateAccountNumber() {
        String accountNumber;
        do {
            accountNumber = ACCOUNT_NUMBER_PREFIX + String.valueOf(
                    ACCOUNT_NUMBER_PAD_OFFSET + ThreadLocalRandom.current().nextInt(ACCOUNT_NUMBER_RANDOM_BOUND)).substring(1);
        } while (accountRepository.findByAccountNumber(accountNumber).isPresent());
        return accountNumber;
    }

    /**
     * 예치금 월 이자 지급(AccountInterestJob이 매월 1일 계좌마다 호출). balance(예치금) 기준으로
     * Account.calculateMonthlyInterest()만큼 지급하고 원장에 INTEREST로 남긴다. 이자는 수익률에
     * 잡히지 않도록 Account.applyInterest()가 baseBalance도 함께 올린다.
     *
     * 같은 달에 잡이 두 번 돌아도(서버 재시작·다중 인스턴스) 중복 지급되지 않도록, paidSince 이후
     * INTEREST 원장이 이미 있으면 건너뛴다. 주문 체결과 같은 계좌를 동시에 건드릴 수 있어 관리자
     * 잔고 조정과 동일하게 비관적 락으로 조회한다.
     *
     * @param paidSince 이번 달 1일 0시(KST)를 JVM 기본 시간대로 바꾼 값 — 원장 createdAt과 같은 기준
     * @param interestMonth 이자 대상 월(지급일 기준 지난달), 원장 사유 문구용
     */
    @Transactional
    public void payMonthlyInterest(Long accountId, LocalDateTime paidSince, int interestMonth) {
        Account account = accountRepository.findAccountWithUserByIdForUpdate(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (accountTransactionService.existsTransactionSince(accountId, AccountTransactionType.INTEREST, paidSince)) {
            return;
        }
        long interest = account.calculateMonthlyInterest();
        if (interest <= 0) {
            return;
        }
        long balanceBefore = account.getBalance();
        account.applyInterest(interest);
        accountTransactionService.record(account, AccountTransactionType.INTEREST, interest, balanceBefore,
                null, null, null, String.format("%d월 예치금 이자(연 %s%%)", interestMonth, account.getInterestRate()));
        log.info("[AccountService] 예치금 이자 지급 - accountId: {}, interest: {}", accountId, interest);
    }

    /**
     * accountId+userId로 계좌를 조회하고 소유권까지 함께 검증한다. AccountService/OrderService
     * 여러 메서드(getProfit, createMarketOrder, createLimitOrder, getMyOrderHistory,
     * getMyHoldings)가 각자 findByAccountIdAndUserId(...).orElseThrow(ACCOUNT_NOT_FOUND)를
     * 복붙해 쓰던 것을 이 메서드 하나로 모았다 — 소유권 검증 정책이 바뀌면(예: 정지 계좌 처리
     * 방식 변경) 한 곳만 고치면 된다.
     */
    @Transactional(readOnly = true)
    public Account getOwnedAccount(Long userId, Long accountId) {
        return accountRepository.findByAccountIdAndUserId(accountId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    /**
     * getOwnedAccount()와 동일하지만 비관적 락(SELECT ... FOR UPDATE)을 건다. 계좌 하나를 두고
     * "확인 후 실행(check-then-act)" 패턴이 필요한 호출부(예: ChargeRequestService.createRequest()의
     * "PENDING 요청 중복 확인 → 저장" 사이 경합 방지)가 chargeBalance()와 동일한 잠금 방식을
     * 복붙하지 않고 재사용하도록 이 메서드로 뽑았다(코드리뷰 반영, 2026-09).
     */
    @Transactional
    public Account getOwnedAccountForUpdate(Long userId, Long accountId) {
        return accountRepository.findByAccountIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    /**
     * 계좌 수익률 조회. 계좌 A/B/C는 서로 독립된 영역이라 항상 계좌 하나 단위로 계산한다
     * (schema.sql "총 자산 계산 참고").
     *
     * 총 자산 = (balance + frozenBalance) + Σ(holdings.quantity × 현재가)
     * 수익률  = (총 자산 - baseBalance) / baseBalance × 100
     *
     * 보유종목 시세 평가는 HoldingValuationService.getHoldingValuations()로 한 번에 배치
     * 조회한다(종목마다 Redis를 순차 호출하지 않기 위함, OrderService.getMyHoldings()와
     * 동일한 로직을 공유).
     */
    @Transactional(readOnly = true)
    public ProfitResponse getProfit(Long userId, Long accountId) {
        Account account = getOwnedAccount(userId, accountId);

        long stockValuation = holdingValuationService.getHoldingValuations(accountId).stream()
                .mapToLong(valuation -> valuation.currentPrice() * valuation.quantity())
                .sum();

        long totalAsset = account.getBalance() + account.getFrozenBalance() + stockValuation;
        long profitAmount = totalAsset - account.getBaseBalance();
        double profitRate = account.getBaseBalance() == 0
                ? 0.0
                : profitAmount * 100.0 / account.getBaseBalance();

        return ProfitResponse.of(totalAsset, profitAmount, profitRate);
    }
}
