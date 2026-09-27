package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.AppointmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 居民回收预约 */
@Getter
@Setter
@Entity
@Table(name = "appointment", uniqueConstraints = @UniqueConstraint(columnNames = "appointmentNo"))
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String appointmentNo;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private Long siteId;

    /** 关联的社区活动日，可为空 */
    private Long activityDayId;

    /** 药品类型描述，如 "感冒药、降压药" */
    @Column(nullable = false, length = 256)
    private String drugTypeDesc;

    /** 预估数量（盒/瓶/支） */
    @Column(nullable = false)
    private int estimatedQty;

    /** 是否含处方药 */
    @Column(nullable = false)
    private boolean hasPrescription;

    /** 是否含液体药 */
    @Column(nullable = false)
    private boolean hasLiquid;

    /** 是否含针剂 */
    @Column(nullable = false)
    private boolean hasInjection;

    /** 是否有带个人信息的包装 */
    @Column(nullable = false)
    private boolean hasPersonalInfo;

    /** 预约时的积分规则快照，格式 TABLET:10,ANTIBIOTIC:15,... */
    @Column(nullable = false, length = 512)
    private String pointsRuleSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime checkedInAt;
}
