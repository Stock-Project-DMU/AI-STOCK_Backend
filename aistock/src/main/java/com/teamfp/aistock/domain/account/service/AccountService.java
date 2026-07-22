package com.teamfp.aistock.domain.account.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamfp.aistock.domain.account.dto.request.CreateAccountRequest;
import com.teamfp.aistock.domain.account.dto.response.AccountInfoResponse;
import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.account.repository.AccountRepository;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.repository.UserRepository;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {

    // 계좌 개설/충전 1회당 지급되는 고정 가상캐시. 회원가입 직후 첫 계좌든 이후 유저가 자유롭게
    // 추가하는 계좌든 항상 이 금액으로 시작한다(schema.sql accounts.balance/base_balance
    // DEFAULT와 동일한 값).
    private static final long INITIAL_BALANCE = 10_000_000L;
    // 계좌 1개당 자동 충전 최대 횟수. 이 횟수를 넘기면 문의(inquiries)를 통해 관리자 승인이 필요하다.
    private static final int MAX_CHARGE_COUNT = 3;
    // 유저 1명이 만들 수 있는 최대 계좌 수(성향별로 나눠 투자해볼 수 있도록 — 예: 안정형/공격형).
    private static final int MAX_ACCOUNT_COUNT = 3;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AccountInfoResponse> getMyAccounts(Long userId) {
        return accountRepository.findAllByUserId(userId).stream()
                .map(AccountInfoResponse::from)
                .toList();
    }

    /**
     * 계좌 개설. 회원가입 시 자동 생성되는 첫 계좌도, 이후 유저가 추가하는 계좌도 전부 이
     * 메서드를 거친다(feature/auth-signup이 구현되면 그대로 재사용할 수 있도록). 유저 1명당
     * 최대 3개(MAX_ACCOUNT_COUNT)까지만 허용한다.
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

        return AccountInfoResponse.from(account);
    }

    /**
     * 가상캐시 충전. 금액은 고정(INITIAL_BALANCE와 동일한 1000만원)이고 시점은 유저 자유다
     * (연속으로 3번 다 써도 무방). 계좌의 chargeCount가 MAX_CHARGE_COUNT(3)에 도달하면 더 이상
     * 자동 충전을 허용하지 않고, 문의(inquiries) 기능으로 관리자에게 요청하도록 안내한다.
     *
     * 계좌를 findByAccountIdAndUserIdForUpdate로 비관적 락을 걸어 조회한다 — Account.version
     * (낙관적 락)만으로는 chargeCount==2인 상태에서 동시에 두 번째 충전 요청이 들어왔을 때,
     * 순서대로 처리했다면 정상 처리됐을 요청까지 OPTIMISTIC_LOCK_CONFLICT(409)로 실패해버린다.
     * 비관적 락으로 두 요청을 순서대로 처리하면, 나중 요청은 먼저 커밋된 chargeCount를 다시 보고
     * 정확히 CHARGE_LIMIT_EXCEEDED 여부를 판단하게 된다.
     */
    @Transactional
    public AccountInfoResponse chargeBalance(Long userId, Long accountId) {
        Account account = accountRepository.findByAccountIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getChargeCount() >= MAX_CHARGE_COUNT) {
            throw new CustomException(ErrorCode.CHARGE_LIMIT_EXCEEDED);
        }
        account.chargeBalance(INITIAL_BALANCE);
        return AccountInfoResponse.from(account);
    }

    /**
     * 계좌번호 생성. 실제 증권사 계좌 체계를 흉내낼 필요가 없는 모의투자 서비스라
     * UUID 일부를 잘라 accounts.account_number(UNIQUE, VARCHAR(20))에 맞춘다.
     */
    private String generateAccountNumber() {
        return "VA" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
