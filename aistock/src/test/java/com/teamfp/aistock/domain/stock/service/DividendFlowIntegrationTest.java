package com.teamfp.aistock.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.entity.AccountTransaction;
import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.account.repository.AccountTransactionRepository;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlementStatus;
import com.teamfp.aistock.domain.stock.entity.DividendSchedule;
import com.teamfp.aistock.domain.stock.repository.DividendEntitlementRepository;
import com.teamfp.aistock.domain.stock.repository.DividendScheduleRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;

/**
 * 배당 권리 부여 → 지급 흐름 검증(feature/dividend, 실제 스프링 컨텍스트 + 실제 MySQL 통합 테스트).
 * 잡은 시각(06:00/09:00)에만 돌기 때문에 잡이 호출하는 DividendEntitlementService 메서드를 직접 부른다.
 * 실행 전 로컬 MySQL(3306)·Redis(6379)가 떠 있어야 한다(OrderExecutionIntegrationTest와 동일).
 */
@SpringBootTest
class DividendFlowIntegrationTest {

    @Autowired
    private DividendEntitlementService dividendEntitlementService;

    @Autowired
    private DividendScheduleService dividendScheduleService;

    @Autowired
    private DividendScheduleRepository dividendScheduleRepository;

    @Autowired
    private DividendEntitlementRepository dividendEntitlementRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountTransactionRepository accountTransactionRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    @Qualifier("batchTaskExecutor")
    private Executor batchTaskExecutor;

    /**
     * 서버 기동 시 DividendEntitlementJob(스케줄 적재)·DividendPaymentJob(놓친 지급)이 단일 스레드
     * batchTaskExecutor에서 비동기로 돈다. 그 작업이 테스트가 만든 권리를 먼저 지급해버리지 않도록,
     * 빈 작업 하나를 넣고 끝날 때까지 기다려 앞선 기동 작업이 모두 끝난 뒤에 시작한다.
     */
    @BeforeEach
    void waitForStartupBatchTasks() throws Exception {
        CompletableFuture.runAsync(() -> { }, batchTaskExecutor).get();
    }

    @Test
    void 배당락일_보유_계좌에_권리를_만들고_지급일에_예수금으로_입금한다() {
        long uniqueSuffix = System.currentTimeMillis();
        // 실제 종목의 배당 회차·보유 계좌와 섞이지 않도록 매번 별도 종목코드를 쓴다.
        String stockCode = "D" + lastFive(Long.toString(uniqueSuffix, 36).toUpperCase());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        Account account = createAccountHolding(uniqueSuffix, stockCode, 10);
        // 지급일 미공시(payDate null) + 기준일이 46일 전 → 대체 지급일(기준일+45일)이 어제라 오늘 지급 대상
        DividendSchedule schedule = dividendScheduleRepository.save(DividendSchedule.builder()
                .stockCode(stockCode)
                .fiscalYear(String.valueOf(today.getYear()))
                .period("Q1")
                .dividendKind("QUARTERLY")
                .dpsCash(374)
                .recordDate(today.minusDays(46))
                .exDividendDate(today)
                .payDate(null)
                .source("TEST")
                .build());

        // 1) 권리 부여 — 두 번 불러도 한 건만 생긴다
        assertThat(dividendEntitlementService.grantEntitlements(schedule.getDividendScheduleId())).isEqualTo(1);
        assertThat(dividendEntitlementService.grantEntitlements(schedule.getDividendScheduleId())).isZero();

        DividendEntitlement entitlement = findEntitlement(schedule, account);
        assertThat(entitlement.getQuantity()).isEqualTo(10);
        assertThat(entitlement.getTotalAmount()).isEqualTo(3_740L);
        assertThat(entitlement.getStatus()).isEqualTo(DividendEntitlementStatus.PENDING);
        assertThat(entitlement.getPayDate()).isNull(); // 대체 지급일(기준일+45일 = 어제) 적용 여부는 아래 지급 대상 조회로 확인

        // 2) 지급 — 예수금 입금 + DIVIDEND 원장 + PAID. 다시 불러도 중복 지급되지 않는다
        assertThat(dividendEntitlementService.getDueEntitlementIds(today)).contains(entitlement.getDividendEntitlementId());
        dividendEntitlementService.payEntitlement(entitlement.getDividendEntitlementId());
        dividendEntitlementService.payEntitlement(entitlement.getDividendEntitlementId());

        Account paidAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(paidAccount.getBalance()).isEqualTo(1_003_740L);
        assertThat(paidAccount.getBaseBalance()).isEqualTo(1_000_000L); // 배당은 수익률에 수익으로 잡힌다

        List<AccountTransaction> dividendTransactions = accountTransactionRepository
                .findAllByAccount_AccountIdAndTypeIn(account.getAccountId(), List.of(AccountTransactionType.DIVIDEND));
        assertThat(dividendTransactions).hasSize(1);
        assertThat(dividendTransactions.get(0).getAmount()).isEqualTo(3_740L);
        assertThat(dividendTransactions.get(0).getReason()).contains("배당금 입금");

        DividendEntitlement paid = findEntitlement(schedule, account);
        assertThat(paid.getStatus()).isEqualTo(DividendEntitlementStatus.PAID);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(dividendScheduleService.getMyDividends(account.getUser().getUserId()))
                .extracting(response -> response.totalAmount())
                .containsExactly(3_740L);
    }

    @Test
    void 배당금이_미공시인_회차는_권리를_만들지_않고_계좌가_없으면_지급을_건너뛴다() {
        long uniqueSuffix = System.currentTimeMillis() + 1;
        String stockCode = "E" + lastFive(Long.toString(uniqueSuffix, 36).toUpperCase());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        createAccountHolding(uniqueSuffix, stockCode, 5);

        DividendSchedule undisclosed = dividendScheduleRepository.save(DividendSchedule.builder()
                .stockCode(stockCode).fiscalYear(String.valueOf(today.getYear())).period("Q2")
                .dpsCash(null).recordDate(today.plusDays(1)).exDividendDate(today).source("TEST").build());
        assertThat(dividendEntitlementService.grantEntitlements(undisclosed.getDividendScheduleId())).isZero();

        // 탈퇴로 계좌가 사라진 경우: 존재하지 않는 accountId의 권리를 직접 만든다
        DividendSchedule disclosed = dividendScheduleRepository.save(DividendSchedule.builder()
                .stockCode(stockCode).fiscalYear(String.valueOf(today.getYear())).period("Q3")
                .dpsCash(100).recordDate(today.minusDays(50)).exDividendDate(today.minusDays(51))
                .payDate(today).source("TEST").build());
        DividendEntitlement orphan = dividendEntitlementRepository.save(DividendEntitlement.builder()
                .accountId(-uniqueSuffix).dividendSchedule(disclosed).quantity(5).build());

        assertThatThrownBy(() -> dividendEntitlementService.payEntitlement(orphan.getDividendEntitlementId()))
                .isInstanceOf(CustomException.class);
        dividendEntitlementService.skipEntitlement(orphan.getDividendEntitlementId());
        assertThat(dividendEntitlementRepository.findById(orphan.getDividendEntitlementId()).orElseThrow().getStatus())
                .isEqualTo(DividendEntitlementStatus.SKIPPED);
    }

    @Test
    void 지급일이_미공시면_기준일_45일_뒤가_지급일이고_그_전날까지는_지급하지_않는다() {
        long uniqueSuffix = System.currentTimeMillis() + 2;
        String stockCode = "F" + lastFive(Long.toString(uniqueSuffix, 36).toUpperCase());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        createAccountHolding(uniqueSuffix, stockCode, 3);

        // 기준일 44일 전 → 대체 지급일은 내일(기준일+45일)이라 오늘은 지급 대상이 아니다
        DividendSchedule schedule = dividendScheduleRepository.save(DividendSchedule.builder()
                .stockCode(stockCode).fiscalYear(String.valueOf(today.getYear())).period("Q4")
                .dpsCash(200).recordDate(today.minusDays(44)).exDividendDate(today.minusDays(45))
                .payDate(null).source("TEST").build());
        assertThat(schedule.resolvePayDate()).isEqualTo(today.minusDays(44).plusDays(DividendSchedule.PAY_DATE_FALLBACK_DAYS));
        assertThat(schedule.isPayDateEstimated()).isTrue();

        assertThat(dividendEntitlementService.grantEntitlements(schedule.getDividendScheduleId())).isEqualTo(1);
        Long entitlementId = dividendEntitlementRepository.findAll().stream()
                .filter(e -> e.getDividendSchedule().getDividendScheduleId().equals(schedule.getDividendScheduleId()))
                .findFirst().orElseThrow().getDividendEntitlementId();

        assertThat(dividendEntitlementService.getDueEntitlementIds(today)).doesNotContain(entitlementId);
        assertThat(dividendEntitlementService.getDueEntitlementIds(today.plusDays(1))).contains(entitlementId);
    }

    @Test
    void 같은_권리를_동시에_지급해도_한_번만_입금된다() throws Exception {
        long uniqueSuffix = System.currentTimeMillis() + 3;
        String stockCode = "G" + lastFive(Long.toString(uniqueSuffix, 36).toUpperCase());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Account account = createAccountHolding(uniqueSuffix, stockCode, 10);
        DividendSchedule schedule = dividendScheduleRepository.save(DividendSchedule.builder()
                .stockCode(stockCode).fiscalYear(String.valueOf(today.getYear())).period("Q1")
                .dpsCash(500).recordDate(today.minusDays(10)).exDividendDate(today.minusDays(11))
                .payDate(today).source("TEST").build());
        dividendEntitlementService.grantEntitlements(schedule.getDividendScheduleId());
        Long entitlementId = findEntitlement(schedule, account).getDividendEntitlementId();

        // 지급 잡이 겹쳐 도는 상황(09:00 정각 기동, 다중 서버)을 두 스레드 동시 호출로 재현한다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = List.of(
                    pool.submit(() -> { start.await(); dividendEntitlementService.payEntitlement(entitlementId); return null; }),
                    pool.submit(() -> { start.await(); dividendEntitlementService.payEntitlement(entitlementId); return null; }));
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()).isEqualTo(1_005_000L);
        assertThat(accountTransactionRepository
                .findAllByAccount_AccountIdAndTypeIn(account.getAccountId(), List.of(AccountTransactionType.DIVIDEND)))
                .hasSize(1);
    }

    private Account createAccountHolding(long uniqueSuffix, String stockCode, int quantity) {
        User user = userRepository.save(User.builder()
                .loginId("dividend-test-user-" + uniqueSuffix)
                .name("배당테스트유저")
                .role(Role.USER)
                .isActive(true)
                .build());
        Account account = accountRepository.save(Account.builder()
                .user(user)
                .accountName("배당테스트계좌")
                .accountNumber("DV" + (uniqueSuffix % 10_000_000L))
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build());
        holdingRepository.save(Holding.builder()
                .account(account)
                .stockCode(stockCode)
                .stockName("배당테스트종목")
                .quantity(quantity)
                .avgPrice(50_000L)
                .build());
        return account;
    }

    private static String lastFive(String text) {
        return text.substring(text.length() - 5);
    }

    private DividendEntitlement findEntitlement(DividendSchedule schedule, Account account) {
        return dividendEntitlementRepository.findAll().stream()
                .filter(e -> e.getDividendSchedule().getDividendScheduleId().equals(schedule.getDividendScheduleId())
                        && e.getAccountId().equals(account.getAccountId()))
                .findFirst()
                .orElseThrow();
    }
}
