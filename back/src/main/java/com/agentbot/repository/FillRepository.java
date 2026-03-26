package com.agentbot.repository;

import com.agentbot.model.Fill;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillRepository extends JpaRepository<Fill, Long> {
}
