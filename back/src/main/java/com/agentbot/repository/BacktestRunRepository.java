package com.agentbot.repository;

import com.agentbot.model.BacktestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BacktestRunRepository extends JpaRepository<BacktestRunEntity, Long> {
    Optional<BacktestRunEntity> findByRunId(String runId);
    List<BacktestRunEntity> findByStressProfileOrderByCreatedAtDesc(String stressProfile);
    List<BacktestRunEntity> findTop50ByOrderByCreatedAtDesc();
}
