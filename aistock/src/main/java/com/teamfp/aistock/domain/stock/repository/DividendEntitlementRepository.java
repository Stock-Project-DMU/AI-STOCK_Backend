package com.teamfp.aistock.domain.stock.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.stock.entity.DividendEntitlement;
import com.teamfp.aistock.domain.stock.entity.DividendEntitlementStatus;

import jakarta.persistence.LockModeType;

public interface DividendEntitlementRepository extends JpaRepository<DividendEntitlement, Long> {

    // 같은 회차에 이미 권리가 만들어진 계좌(잡 재실행 시 uq_dividend_entitlement 위반 없이 건너뛰기용)
    @Query("select e.accountId from DividendEntitlement e where e.dividendSchedule.dividendScheduleId = :dividendScheduleId")
    Set<Long> findAccountIdsByDividendScheduleId(@Param("dividendScheduleId") Long dividendScheduleId);

    // 지급·건너뜀 처리용 비관적 락. 지급 잡이 겹쳐 돌아도(09:00 정각 기동, 다중 서버) 두 번째 실행은 앞 실행이
    // 커밋할 때까지 기다린 뒤 최신 상태(PAID)를 읽어 중복 지급하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DividendEntitlement e join fetch e.dividendSchedule where e.dividendEntitlementId = :dividendEntitlementId")
    Optional<DividendEntitlement> findByIdForUpdate(@Param("dividendEntitlementId") Long dividendEntitlementId);

    // 지급 대상 후보(PENDING 전체). 지급일 판단에 회차의 지급일·기준일이 필요해 회차를 함께 읽는다.
    @Query("select e from DividendEntitlement e join fetch e.dividendSchedule where e.status = :status")
    List<DividendEntitlement> findAllWithScheduleByStatus(@Param("status") DividendEntitlementStatus status);

    // 내 배당 수령 내역(PAID, 지급 시각 최신순) / 내 지급 대기 권리(PENDING)
    @Query("select e from DividendEntitlement e join fetch e.dividendSchedule"
            + " where e.accountId in :accountIds and e.status = :status order by e.paidAt desc, e.dividendEntitlementId desc")
    List<DividendEntitlement> findAllWithScheduleByAccountIdInAndStatus(@Param("accountIds") Collection<Long> accountIds,
            @Param("status") DividendEntitlementStatus status);
}
