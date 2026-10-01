package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.DrugCategory;
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

    /** 药盒是否带姓名、身份证号、诊断等个人信息包装，需要隐私处理 */
    @Column(nullable = false)
    private boolean privacyPackaging = false;

    /** 隐私处理未完成（居民拒绝、待复核或复核不合格）时积分暂缓发放 */
    @Column(nullable = false)
    private boolean pointsWithheld = false;

    /** 隐私复核结论为按无积分回收：药品收下继续流转，但该袋积分永久不再发放 */
    @Column(nullable = false)
    private boolean pointsForfeited = false;

    /** 本袋应发积分 */
    @Column(nullable = false)
    private int points = 0;

    @Column(nullable = false)
    private boolean pointsGranted = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BagStatus status = BagStatus.ACCEPTED;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
