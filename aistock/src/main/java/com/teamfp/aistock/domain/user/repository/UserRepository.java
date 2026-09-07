package com.teamfp.aistock.domain.user.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
import com.teamfp.aistock.global.util.StatisticsPointProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByEmail(String email);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<User> findByUserIdAndIsActiveTrue(Long userId);

    long countByIsActiveTrue();

    // 관리자 사용자 목록 — 탈퇴(deactivate) 유저는 목록에서 제외한다(feature/admin-user 코드리뷰 반영).
    Page<User> findAllByIsActiveTrue(Pageable pageable);

    // 관리자 탈퇴 회원 조회(ADMIN_API_BACKEND_HANDOFF.md 5.4 옵션2, feature/admin-api-p0).
    // 익명화(deactivate)된 유저만 대상이다. 최종 정책은 아직 팀 확정 전이라, 지금은 "익명화된
    // 탈퇴 이력만 별도로 보여준다"는 옵션2로 구현해뒀다 — 나중에 다른 옵션으로 바뀌면 이 메서드와
    // 호출부만 교체하면 된다.
    Page<User> findAllByIsActiveFalse(Pageable pageable);

    // 관리자 회원 검색·필터(feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 3.2). query는
    // 회원번호(userId)/아이디/이름/이메일 통합검색이고, status/role은 선택 필터다. 파라미터가
    // null이면 해당 조건 자체를 걸지 않는다 — 컨트롤러가 빈 문자열을 null로 정규화해서 넘긴다.
    // 이전처럼 findAllByIsActiveTrue(pageable)로 한 페이지만 가져온 뒤 애플리케이션에서
    // 걸러내면 DB 전체가 아니라 그 페이지 안에서만 검색되는 문제가 있어(3.2 요구사항), 조건을
    // 전부 쿼리 레벨로 내렸다. str(u.userId)는 "12"를 넣었을 때 userId=12뿐 아니라 120, 512처럼
    // "12"를 포함하는 다른 회원번호까지 부분일치되는 것을 감안한 선택이다 — 회원번호 완전일치
    // 검색이 필요해지면 별도로 분리해야 한다.
    @Query("select u from User u where u.isActive = true "
            + "and (:query is null or str(u.userId) like concat('%', :query, '%') "
            + "or u.loginId like concat('%', :query, '%') "
            + "or u.name like concat('%', :query, '%') "
            + "or u.email like concat('%', :query, '%')) "
            + "and (:status is null or u.status = :status) "
            + "and (:role is null or u.role = :role)")
    Page<User> searchUsers(@Param("query") String query, @Param("status") UserStatus status,
            @Param("role") Role role, Pageable pageable);

    // 마지막 남은 관리자인지 확인(check) + 정지 반영(act) 사이에 비관적 락으로 동시 요청을
    // 순서대로 처리한다(AdminUserService.validateSuspendable()용). 활성 ADMIN이 정확히 2명일 때
    // 서로 다른 admin을 동시에 정지시키는 두 요청이 락 없이 각자 "정지 전 카운트=2"를 보고
    // 둘 다 통과해버리면 활성 admin이 0명(lockout)이 될 수 있어, AccountRepository.
    // findAllByUserIdForUpdate와 동일한 패턴(잠금 대상 행 목록을 그대로 개수 확인에도 재사용)으로
    // 대상 admin 행들을 잠근다 — 두 번째 요청은 첫 번째 요청이 커밋(상태 변경 반영)할 때까지
    // 대기했다가 갱신된 목록을 다시 봐서 정확히 판단한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.status = :status and u.isActive = true")
    List<User> findAllByRoleAndStatusAndIsActiveTrueForUpdate(@Param("role") Role role, @Param("status") UserStatus status);

    // 관리자 알림 전체 발송(feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 4.4)용 — 탈퇴
    // (isActive=false) 유저는 알림을 못 받아도 상관없으므로 제외하고 대상 userId만 뽑는다.
    // User 전체 엔티티를 로딩할 필요 없이 id만 있으면 되므로 List<Long>으로 좁혀 조회한다.
    @Query("select u.userId from User u where u.isActive = true")
    List<Long> findAllActiveUserIds();

    // 관리자 기간별 통계 — 가입자 추이(feature/admin-api-p0, ADMIN_API_BACKEND_HANDOFF.md 6.1).
    // JPQL은 MySQL 전용 함수(date_format)를 못 쓰므로 네이티브 쿼리로 작성한다. pattern은
    // StatisticsInterval의 %Y-%m-%d/%x-%v/%Y-%m 중 하나이며, DATE_FORMAT의 포맷 문자열도 일반
    // 파라미터처럼 바인딩된다(리터럴 문자열이라 SQL 인젝션 우려 없음 — 컨트롤러가 enum으로
    // 받아 세 값 중 하나만 넘어온다).
    @Query(value = "select date_format(created_at, :pattern) as period, count(*) as value "
            + "from users where is_active = true and created_at between :from and :to "
            + "group by period order by period", nativeQuery = true)
    List<StatisticsPointProjection> aggregateUserSignups(
            @Param("pattern") String pattern, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
