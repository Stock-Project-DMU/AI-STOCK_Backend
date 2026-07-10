package com.teamfp.aistock.domain.user.repository;

import com.teamfp.aistock.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
