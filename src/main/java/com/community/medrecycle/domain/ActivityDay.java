package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 社区活动日：大批量回收专场，支持积分倍率与预约名额控制 */
@Getter
@Setter
@Entity
@Table(name = "activity_day")
public class ActivityDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private LocalDate activityDate;

    /** 活动日积分倍率，如 1.5 */
    @Column(nullable = false)
    private double pointsMultiplier = 1.0;

    /** 活动日最大预约数（大批量回收容量控制） */
    @Column(nullable = false)
    private int maxAppointments;

    @Column(nullable = false)
    private int bookedCount = 0;

    @Version
    private long version;
}
