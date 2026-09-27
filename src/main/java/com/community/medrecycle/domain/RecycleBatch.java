package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.BatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 回收批次：预约、称重、复核、积分、暂存、交接、异常都挂在同一批次上 */
@Getter
@Setter
@Entity
@Table(name = "recycle_batch", uniqueConstraints = @UniqueConstraint(columnNames = "batchNo"))
public class RecycleBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String batchNo;

    @Column(nullable = false)
    private Long appointmentId;

    @Column(nullable = false, length = 32)
    private String appointmentNo;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private Long siteId;

    /** 现场登记回收员 */
    @Column(nullable = false)
    private Long recyclerId;

    private Long activityDayId;

    /** 本批次积分倍率（活动日生效），登记时固化 */
    @Column(nullable = false)
    private double pointsMultiplier = 1.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BatchStatus status = BatchStatus.CHECKED_IN;

    @Column(nullable = false)
    private double totalWeightKg = 0;

    @Column(nullable = false)
    private int totalPoints = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
