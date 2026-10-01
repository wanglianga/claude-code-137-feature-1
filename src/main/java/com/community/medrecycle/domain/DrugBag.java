package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 封袋：每袋药品有来源（批次/预约）与分类，是暂存与交接的最小单位 */
@Getter
@Setter
@Entity
@Table(name = "drug_bag", uniqueConstraints = {
        @UniqueConstraint(columnNames = "bagNo"),
        @UniqueConstraint(columnNames = "sealNo")
}, indexes = {
        @Index(name = "idx_bag_batch", columnList = "batchId"),
        @Index(name = "idx_bag_site_status", columnList = "siteId,status")
})
public class DrugBag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    @Column(nullable = false)
    private double weightKg;

    /** 封袋编号（现场拍摄记录） */
    @Column(nullable = false, length = 64)
    private String sealNo;

    /** 包装与封袋照片，逗号分隔 */
    @Column(length = 1024)
    private String photoUrls;

    @Column(nullable = false)
    private boolean highRisk;

    /** 本袋应发积分 */
    @Column(nullable = false)
    private int points = 0;

    @Column(nullable = false)
    private boolean pointsGranted = false;

    /** 隐私包装未处理合格：积分暂缓发放（复核通过后补发，或按无积分回收清零） */
    @Column(nullable = false)
    private boolean pointsWithheld = false;

    /** 隐私包装处理状态，默认无个人信息无需处理 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrivacyStatus privacyStatus = PrivacyStatus.NOT_REQUIRED;

    /** 关联的隐私包装处理记录 */
    private Long privacyTreatmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BagStatus status = BagStatus.ACCEPTED;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
