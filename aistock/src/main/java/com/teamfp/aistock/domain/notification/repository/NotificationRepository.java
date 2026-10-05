package com.teamfp.aistock.domain.notification.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("select n from Notification n where n.user.userId = :userId order by n.createdAt desc")
    List<Notification> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("select count(n) from Notification n where n.user.userId = :userId and n.isRead = false")
    long countByUserIdAndIsReadFalse(@Param("userId") Long userId);

    // 공지 팝업(feat/admin-improvements) — 내가 받은 공지 중 팝업 기한이 오늘 이후(포함)인 것, 최신 공지순.
    // 닫은 기록은 따로 없다 — 로그인할 때마다 기한까지 다시 띄운다(띄우는 시점은 프론트가 로그인 직후로 정한다).
    @Query("select n from Notification n join fetch n.notice nt where n.user.userId = :userId "
            + "and nt.popupEndDate >= :today order by nt.createdAt desc, nt.noticeId desc")
    List<Notification> findActivePopups(@Param("userId") Long userId, @Param("today") LocalDate today);

    // 관리자 알림 관리 — 공지 상세의 받은 회원 수·읽은 회원 수
    @Query("select count(n) from Notification n where n.notice.noticeId = :noticeId")
    long countByNoticeId(@Param("noticeId") Long noticeId);

    @Query("select count(n) from Notification n where n.notice.noticeId = :noticeId and n.isRead = true")
    long countReadByNoticeId(@Param("noticeId") Long noticeId);

    // 공지 삭제 — 받은 회원들 알림함에서도 함께 지운다. 일괄 삭제라 영속성 컨텍스트에 남은 알림이 지워진 공지를
    // 가리키지 않도록 실행 전에 flush, 실행 후에 clear한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Notification n where n.notice.noticeId = :noticeId")
    int deleteByNoticeId(@Param("noticeId") Long noticeId);

    @Query("select n from Notification n where n.notiId = :notiId and n.user.userId = :userId")
    Optional<Notification> findByNotiIdAndUserId(@Param("notiId") Long notiId, @Param("userId") Long userId);
}
