package com.teamfp.aistock.domain.account.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.account.entity.AccountTransactionType;
import com.teamfp.aistock.domain.account.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feature/mypage-improvement 코드리뷰 반영 — 서버 기동 시 보충 지급은 이번 달 정기 지급이 아예 안 됐을 때만,
 * 그리고 이번 달 1일 이전에 개설된 계좌에만 이자를 준다.
 */
@ExtendWith(MockitoExtension.class)
class AccountInterestJobTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private AccountTransactionService accountTransactionService;

    private AccountInterestJob accountInterestJob;

    @BeforeEach
    void setUp() {
        accountInterestJob = new AccountInterestJob(accountRepository, accountService, accountTransactionService);
    }

    @Test
    @DisplayName("이번 달 정기 지급 기록이 있으면 서버 기동 시 보충 지급을 하지 않는다")
    void missedRun_skipsWhenThisMonthAlreadyPaid() {
        when(accountTransactionService.existsAnyTransactionSince(eq(AccountTransactionType.INTEREST), any(LocalDateTime.class)))
                .thenReturn(true);

        accountInterestJob.payMissedMonthlyInterest();

        verify(accountRepository, never()).findAllAccountIdsOpenedBefore(any());
        verify(accountService, never()).payMonthlyInterest(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("이번 달 정기 지급 기록이 없으면 이번 달 1일 이전에 개설된 계좌에만 보충 지급한다")
    void missedRun_paysOnlyAccountsOpenedBeforeMonthStart() {
        when(accountTransactionService.existsAnyTransactionSince(eq(AccountTransactionType.INTEREST), any(LocalDateTime.class)))
                .thenReturn(false);
        when(accountRepository.findAllAccountIdsOpenedBefore(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L));

        accountInterestJob.payMissedMonthlyInterest();

        ArgumentCaptor<LocalDateTime> openedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(accountRepository).findAllAccountIdsOpenedBefore(openedBefore.capture());
        // 기준 시각은 이번 달 1일 0시(시간대 변환 후에도 분·초는 0)
        assertThat(openedBefore.getValue().getMinute()).isZero();
        assertThat(openedBefore.getValue().getSecond()).isZero();
        verify(accountService).payMonthlyInterest(eq(1L), eq(openedBefore.getValue()), anyInt());
        verify(accountService).payMonthlyInterest(eq(2L), eq(openedBefore.getValue()), anyInt());
    }
}
