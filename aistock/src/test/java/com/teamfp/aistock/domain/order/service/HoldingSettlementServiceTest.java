package com.teamfp.aistock.domain.order.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.teamfp.aistock.domain.account.entity.Account;
import com.teamfp.aistock.domain.order.entity.Holding;
import com.teamfp.aistock.domain.order.repository.HoldingRepository;
import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.global.exception.CustomException;
import com.teamfp.aistock.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * feature/order-limit — HoldingSettlementService 단위 테스트.
 *
 * OrderService(시장가)와 OrderExecutionService(지정가)가 각자 갖고 있던 중복 로직을
 * 이 클래스로 합쳤으므로, 두 서비스의 실행 흐름과 별개로 이 클래스 자체의 동작을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class HoldingSettlementServiceTest {

    @Mock
    private HoldingRepository holdingRepository;

    @InjectMocks
    private HoldingSettlementService holdingSettlementService;

    private static final String STOCK_CODE = "005930";

    private Account account;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .loginId("tester")
                .name("테스터")
                .role(Role.USER)
                .isActive(true)
                .build();

        account = Account.builder()
                .user(user)
                .accountNumber("ACC-0001")
                .openedAt(LocalDate.now())
                .baseBalance(1_000_000L)
                .balance(1_000_000L)
                .build();
    }

    @Test
    @DisplayName("increaseOrCreate() - 처음 보유하는 종목이면 새 Holding을 저장한다")
    void increaseOrCreate_createsNewHolding() {
        when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());

        holdingSettlementService.increaseOrCreate(account, STOCK_CODE, "삼성전자", 10, 70_000L);

        verify(holdingRepository).save(any(Holding.class));
    }

    @Test
    @DisplayName("increaseOrCreate() - 이미 보유 중인 종목이면 수량을 더하고 평단가를 가중평균으로 재계산한다")
    void increaseOrCreate_updatesExistingHolding() {
        Holding existing = Holding.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .quantity(10)
                .avgPrice(1_000L)
                .build();
        when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.of(existing));

        holdingSettlementService.increaseOrCreate(account, STOCK_CODE, "삼성전자", 5, 2_000L);

        // (1000*10 + 2000*5) / 15 = 1333 (원단위 내림)
        assertThat(existing.getQuantity()).isEqualTo(15);
        assertThat(existing.getAvgPrice()).isEqualTo(1_333L);
        verify(holdingRepository, never()).save(any(Holding.class));
    }

    @Test
    @DisplayName("increaseOrCreate() - 신규 종목 저장 시 uq_account_stock 유니크 제약 위반이면 OPTIMISTIC_LOCK_CONFLICT로 변환한다")
    void increaseOrCreate_convertsUniqueConstraintViolation() {
        when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());
        when(holdingRepository.save(any(Holding.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_account_stock"));

        assertThatThrownBy(() -> holdingSettlementService.increaseOrCreate(account, STOCK_CODE, "삼성전자", 10, 70_000L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
    }

    @Test
    @DisplayName("increaseOrCreate() - uq_account_stock이 원인이 아닌 저장 실패는 재시도 안내로 감추지 않고 원래 예외를 그대로 던진다")
    void increaseOrCreate_rethrowsNonUniqueConstraintViolation() {
        when(holdingRepository.findByAccountIdAndStockCode(any(), anyString())).thenReturn(Optional.empty());
        org.springframework.dao.DataIntegrityViolationException dataError =
                new org.springframework.dao.DataIntegrityViolationException("stock_name 컬럼 길이 초과");
        when(holdingRepository.save(any(Holding.class))).thenThrow(dataError);

        // uq_account_stock 유니크 제약 위반이 아닌 다른 데이터 문제는 재시도해도 절대 해소되지
        // 않으므로, OPTIMISTIC_LOCK_CONFLICT로 감추지 말고 원래 예외를 그대로 올려보내야 한다.
        assertThatThrownBy(() -> holdingSettlementService.increaseOrCreate(account, STOCK_CODE, "삼성전자", 10, 70_000L))
                .isSameAs(dataError);
    }

    @Test
    @DisplayName("decrease() - 전량 매도가 아니면 수량만 줄이고 행은 남긴다")
    void decrease_partialSell_keepsRow() {
        Holding holding = Holding.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .quantity(10)
                .avgPrice(50_000L)
                .build();

        holdingSettlementService.decrease(holding, 4);

        assertThat(holding.getQuantity()).isEqualTo(6);
        verify(holdingRepository, never()).delete(any());
    }

    @Test
    @DisplayName("decrease() - 전량 매도로 수량이 0이 되면 행 자체를 삭제한다")
    void decrease_fullSell_deletesRow() {
        Holding holding = Holding.builder()
                .account(account)
                .stockCode(STOCK_CODE)
                .stockName("삼성전자")
                .quantity(10)
                .avgPrice(50_000L)
                .build();

        holdingSettlementService.decrease(holding, 10);

        assertThat(holding.getQuantity()).isZero();
        verify(holdingRepository).delete(holding);
    }
}
