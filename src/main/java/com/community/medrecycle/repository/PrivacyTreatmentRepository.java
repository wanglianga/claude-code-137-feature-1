package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PrivacyTreatment;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrivacyTreatmentRepository extends JpaRepository<PrivacyTreatment, Long> {
    Optional<PrivacyTreatment> findByTreatmentNo(String treatmentNo);
    Optional<PrivacyTreatment> findByBagId(Long bagId);
    List<PrivacyTreatment> findByBatchId(Long batchId);
    List<PrivacyTreatment> findByStatusOrderByCreatedAtAsc(PrivacyStatus status);
    long countByStatus(PrivacyStatus status);
}
