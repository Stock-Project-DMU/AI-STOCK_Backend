package com.teamfp.aistock.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.order.entity.Holding;

import jakarta.persistence.LockModeType;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    @Query("select h from Holding h where h.account.accountId = :accountId")
    List<Holding> findAllByAccountId(@Param("accountId") Long accountId);

    // 매수/매도 체결 시 같은 보유종목 행을 동시에 읽고 고쳐서 수량·평단가가 유실되는(lost update)
    // 경합을 막기 위해 비관적 락(SELECT ... FOR UPDATE)으로 조회한다. holdings 테이블에
    // version 컬럼을 추가하는 낙관적 락 대신 이 방식을 쓰는 이유는 스키마 변경 없이도
    // 같은 경합을 막을 수 있기 때문이다(CLAUDE.md 12번 — 새 컬럼 추가는 사전 확인 필요).
    //
    // 주의: 이 락은 "이미 존재하는 행"만 잠근다. 같은 계좌·종목을 처음 매수하는 요청 두 개가
    // 동시에 들어와 둘 다 이 쿼리에서 빈 값을 보는 경우(아직 잠글 행 자체가 없음)는 이 락으로
    // 막을 수 없다 — 그 경합은 holdings.uq_account_stock 유니크 제약과
    // OrderService.executeBuy()의 DataIntegrityViolationException 처리로 별도로 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.account.accountId = :accountId and h.stockCode = :stockCode")
    Optional<Holding> findByAccountIdAndStockCode(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);
}
