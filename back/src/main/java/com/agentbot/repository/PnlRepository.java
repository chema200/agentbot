package com.agentbot.repository;

import com.agentbot.model.PnlRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PnlRepository extends JpaRepository<PnlRecord, Long> {
}
