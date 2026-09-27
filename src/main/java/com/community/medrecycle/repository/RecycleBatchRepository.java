package com.community.medrecycle.repository;

import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.enums.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecycleBatchRepository extends JpaRepository<RecycleBatch, Long> {
    Optional<RecycleBatch> findByBatchNo(String batchNo);
    List<RecycleBatch> findBySiteIdAndStatus(Long siteId, BatchStatus status);
    List<RecycleBatch> findByResidentIdOrderByCreatedAtDesc(Long residentId);
}
