package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsRule;
import com.community.medrecycle.domain.enums.DrugCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PointsRuleRepository extends JpaRepository<PointsRule, Long> {
    Optional<PointsRule> findByCategory(DrugCategory category);
    List<PointsRule> findByActiveTrue();
}
