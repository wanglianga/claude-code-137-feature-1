package com.community.medrecycle.repository;

import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.ReviewKind;
import com.community.medrecycle.domain.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, Long> {
    Optional<ReviewTask> findByTaskNo(String taskNo);
    List<ReviewTask> findByStatusOrderByCreatedAtAsc(ReviewStatus status);
    List<ReviewTask> findByKindAndStatusOrderByCreatedAtAsc(ReviewKind kind, ReviewStatus status);
    List<ReviewTask> findByBatchId(Long batchId);
    List<ReviewTask> findByBagId(Long bagId);
    List<ReviewTask> findByBagIdAndStatus(Long bagId, ReviewStatus status);
    List<ReviewTask> findByBagIdAndKindAndStatus(Long bagId, ReviewKind kind, ReviewStatus status);
    long countByKindAndStatus(ReviewKind kind, ReviewStatus status);
}
