package com.teamfp.aistock.domain.user.repository;

import java.util.Optional;

import com.teamfp.aistock.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByEmail(String email);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<User> findByUserIdAndIsActiveTrue(Long userId);

    long countByIsActiveTrue();

    // 관리자 사용자 목록 — 탈퇴(deactivate) 유저는 목록에서 제외한다(feature/admin-user 코드리뷰 반영).
    Page<User> findAllByIsActiveTrue(Pageable pageable);
}
