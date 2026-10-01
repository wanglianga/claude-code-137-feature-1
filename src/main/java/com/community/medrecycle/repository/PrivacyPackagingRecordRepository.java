package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PrivacyPackagingRecord;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrivacyPackagingRecordRepository extends JpaRepository<PrivacyPackagingRecord, Long> {
    Optional<PrivacyPackagingRecord> findByRecordNo(String recordNo);
    Optional<PrivacyPackagingRecord> findByBagId(Long bagId);
    List<PrivacyPackagingRecord> findByBatchIdOrderByCreatedAtAsc(Long batchId);
    List<PrivacyPackagingRecord> findByStatusOrderByCreatedAtAsc(PrivacyStatus status);
}
