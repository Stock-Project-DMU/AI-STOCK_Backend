package com.teamfp.aistock.domain.user.repository;

import java.util.List;
import java.util.Optional;

import com.teamfp.aistock.domain.user.entity.Role;
import com.teamfp.aistock.domain.user.entity.User;
import com.teamfp.aistock.domain.user.entity.UserStatus;
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
}
