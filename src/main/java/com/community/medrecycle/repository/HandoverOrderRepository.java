package com.community.medrecycle.repository;

import com.community.medrecycle.domain.HandoverOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HandoverOrderRepository extends JpaRepository<HandoverOrder, Long> {
    Optional<HandoverOrder> findByOrderNo(String orderNo);
    List<HandoverOrder> findBySiteIdOrderByCreatedAtDesc(Long siteId);
}
