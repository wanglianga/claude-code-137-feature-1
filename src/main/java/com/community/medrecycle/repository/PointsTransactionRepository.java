package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, Long> {
    List<PointsTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    List<PointsTransaction> findByRefTypeAndRefId(String refType, String refId);
}
