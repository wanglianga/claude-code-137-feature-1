package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsExchangeRepository extends JpaRepository<PointsExchange, Long> {
    List<PointsExchange> findByResidentIdOrderByCreatedAtDesc(Long residentId);
}
