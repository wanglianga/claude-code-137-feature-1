package com.community.medrecycle.repository;

import com.community.medrecycle.domain.ActivityDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityDayRepository extends JpaRepository<ActivityDay, Long> {
    List<ActivityDay> findBySiteId(Long siteId);
}
