package com.agentbot.repository;

import com.agentbot.model.MonteCarloRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonteCarloRunRepository extends JpaRepository<MonteCarloRunEntity, Long> {
    Optional<MonteCarloRunEntity> findByMcRunId(String mcRunId);
    List<MonteCarloRunEntity> findTop20ByOrderByCreatedAtDesc();
}
