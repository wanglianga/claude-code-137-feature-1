package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 积分规则：按药品品类配置每公斤积分，并标记是否高风险需人工复核 */
@Getter
@Setter
@Entity
@Table(name = "points_rule", uniqueConstraints = @UniqueConstraint(columnNames = "category"))
public class PointsRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    /** 每公斤积分 */
    @Column(nullable = false)
    private int pointsPerKg;

    /** 高风险品类，称重后须人工复核通过才发积分 */
    @Column(nullable = false)
    private boolean highRisk;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 256)
    private String description;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
