package com.teamfp.aistock.domain.stock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.stock.entity.RecentViewed;

public interface RecentViewedRepository extends JpaRepository<RecentViewed, Long> {

    @Query("select r from RecentViewed r where r.user.userId = :userId order by r.viewedAt desc")
    List<RecentViewed> findAllByUserIdOrderByViewedAtDesc(@Param("userId") Long userId);

    @Query("select r from RecentViewed r where r.user.userId = :userId and r.stockCode = :stockCode")
    Optional<RecentViewed> findByUserIdAndStockCode(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    // 이미 본 종목을 다시 볼 때 새 행을 추가하지 않고 viewedAt만 갱신하는 용도(RecentViewedService.
    // recordView() 참고). delete-then-insert 방식은 RecentViewed가 @GeneratedValue(IDENTITY)라
    // save()가 즉시 INSERT를 실행해버려서, 아직 flush 안 된 DELETE보다 먼저 INSERT가 나가
    // uq_user_stock_view 유니크 제약을 위반하는 문제가 있었다(Hibernate의 flush 순서도 타입별로
    // INSERT를 DELETE보다 먼저 실행하므로 flush 시점이 같아도 마찬가지). 그래서 새 행을 만드는
    // 대신 UPDATE 한 번으로 끝낸다. 반환값(갱신된 행 수)으로 신규/기존 여부를 판단한다.
    @Modifying
    @Query("update RecentViewed r set r.viewedAt = CURRENT_TIMESTAMP where r.user.userId = :userId and r.stockCode = :stockCode")
    int touchViewedAt(@Param("userId") Long userId, @Param("stockCode") String stockCode);

    @Modifying
    @Query("delete from RecentViewed r where r.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
