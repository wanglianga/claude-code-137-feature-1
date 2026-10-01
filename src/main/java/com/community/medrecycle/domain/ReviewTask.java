package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.ReviewKind;
import com.community.medrecycle.domain.enums.ReviewStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 人工复核任务：高风险药品/异常触发（RISK），或隐私包装社区管理员复核（PRIVACY） */
@Getter
@Setter
@Entity
@Table(name = "review_task", uniqueConstraints = @UniqueConstraint(columnNames = "taskNo"),
        indexes = {
                @Index(name = "idx_review_status", columnList = "status"),
                @Index(name = "idx_review_kind_status", columnList = "kind,status")
        })
public class ReviewTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String taskNo;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    /** 触发原因：HIGH_RISK_CATEGORY / PRIVACY_PENDING / PRIVACY_REWORK / 异常类型 */
    @Column(nullable = false, length = 64)
    private String reason;

    /** 复核类别：RISK=复核员高风险/异常复核；PRIVACY=社区管理员隐私包装复核 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewKind kind = ReviewKind.RISK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    private Long reviewerId;

    @Column(length = 256)
    private String decisionNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime decidedAt;
}
