package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.PrivacyReviewOutcome;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 隐私包装处理记录：居民带来的药盒上可能保留姓名、身份证号、诊断信息。
 * 回收员现场标记隐私包装、引导居民撕除/遮盖并拍摄处理后照片；
 * 居民不同意处理时药品可封袋但积分暂缓，由社区管理员复核。
 * 记录随环保交接批次留存，防止个人信息随药品外流。
 */
@Getter
@Setter
@Entity
@Table(name = "privacy_treatment", uniqueConstraints = @UniqueConstraint(columnNames = "treatmentNo"),
        indexes = {
                @Index(name = "idx_privacy_batch", columnList = "batchId"),
                @Index(name = "idx_privacy_bag", columnList = "bagId"),
                @Index(name = "idx_privacy_status", columnList = "status")
        })
public class PrivacyTreatment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String treatmentNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 32)
    private String batchNo;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrivacyStatus status = PrivacyStatus.TREATED;

    /** 药盒是否带个人信息（姓名/身份证号/诊断） */
    @Column(nullable = false)
    private boolean personalInfoPresent = true;

    /** 现场引导处理的回收员 */
    @Column(nullable = false)
    private Long guidedBy;

    /** 居民是否同意撕除/遮盖；false 表示拒绝处理 */
    private Boolean residentConsent;

    /** 现场处置方式：TEAR_OFF 撕除 / COVERED 遮盖 / REFUSED 居民拒绝 */
    @Column(nullable = false, length = 16)
    private String action = "TEAR_OFF";

    /** 处理后照片（撕除/遮盖信息后拍摄），逗号分隔 */
    @Column(length = 1024)
    private String treatedPhotoUrls;

    @Column(length = 512)
    private String note;

    /** 暂缓积分值，复核通过后据此补发 */
    @Column(nullable = false)
    private int pendingPoints = 0;

    // ---- 社区管理员复核 ----

    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PrivacyReviewOutcome reviewOutcome;

    @Column(length = 512)
    private String reviewNote;

    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
