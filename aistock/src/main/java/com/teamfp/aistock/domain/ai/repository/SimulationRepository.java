package com.teamfp.aistock.domain.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.teamfp.aistock.domain.ai.entity.Simulation;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {

    @Query("select s from Simulation s where s.user.userId = :userId order by s.createdAt desc")
    List<Simulation> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("select s from Simulation s where s.user.userId = :userId and s.simulationId = :simulationId")
    Optional<Simulation> findByUserIdAndSimulationId(@Param("userId") Long userId, @Param("simulationId") Long simulationId);

    @Modifying
    @Query("delete from Simulation s where s.user.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
