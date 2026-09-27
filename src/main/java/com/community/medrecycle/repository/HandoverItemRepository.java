package com.community.medrecycle.repository;

import com.community.medrecycle.domain.HandoverItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HandoverItemRepository extends JpaRepository<HandoverItem, Long> {
    List<HandoverItem> findByOrderId(Long orderId);
    List<HandoverItem> findByBagId(Long bagId);
}
