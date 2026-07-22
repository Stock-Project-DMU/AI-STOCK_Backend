package com.teamfp.aistock.domain.user.repository;

import java.util.Optional;

import com.teamfp.aistock.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByEmail(String email);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<User> findByUserIdAndIsActiveTrue(Long userId);

    long countByIsActiveTrue();
}
