package com.community.medrecycle.repository;

import com.community.medrecycle.domain.WeighRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WeighRecordRepository extends JpaRepository<WeighRecord, Long> {
    List<WeighRecord> findByBatchIdOrderByCreatedAtAsc(Long batchId);
}
