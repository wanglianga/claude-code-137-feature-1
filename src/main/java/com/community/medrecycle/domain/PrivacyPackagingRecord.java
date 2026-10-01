package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.PrivacyMethod;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 隐私包装处理记录：药盒上保留姓名、身份证号、诊断信息时的处置留痕。
 * 回收员现场标记并引导居民撕除/遮盖，拍摄处理后照片；
 * 居民不同意处理则药品可封袋但积分暂缓，交社区管理员复核。
 * 记录随批次进入环保交接，防止个人信息随药品外流。
 */
@Getter
@Setter
@Entity
@Table(name = "privacy_packaging_record", uniqueConstraints = @UniqueConstraint(columnNames = "recordNo"),
        indexes = {
                @Index(name = "idx_privacy_batch", columnList = "batchId"),
                @Index(name = "idx_privacy_bag", columnList = "bagId"),
                @Index(name = "idx_privacy_status", columnList = "status")
        })
public class PrivacyPackagingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String recordNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false, length = 32)
    private String batchNo;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    /** 标记隐私包装的回收员 */
    @Column(nullable = false)
    private Long recyclerId;

    /** 现场处理方式：撕除 / 遮盖；居民拒绝处理时为空 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PrivacyMethod method;

    /** 现场引导说明（如何引导居民撕除或遮盖） */
    @Column(length = 512)
    private String guidanceNote;

    /** 处理后照片（信息已撕除/遮盖后拍摄），逗号分隔；居民拒绝时可为空 */
    @Column(length = 1024)
    private String processedPhotoUrls;

    /** 居民是否同意现场处理个人信息 */
    @Column(nullable = false)
    private boolean residentConsent = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrivacyStatus status = PrivacyStatus.PROCESSED;

    /** 社区管理员复核人 */
    private Long reviewerId;

    /** 复核意见/不合格原因 */
    @Column(length = 512)
    private String reviewNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime reviewedAt;
}
