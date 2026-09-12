package com.teamfp.aistock.domain.ai.repository;

import com.teamfp.aistock.domain.ai.entity.GoalPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GoalPlanRepository extends JpaRepository<GoalPlan, Long> {
    List<GoalPlan> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<GoalPlan> findByPlanIdAndUserId(Long planId, Long userId);
    void deleteByUserId(Long userId);
}
