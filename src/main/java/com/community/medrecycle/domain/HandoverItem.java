package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.DrugCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 环保交接单明细：每袋的来源分类与签收结果 */
@Getter
@Setter
@Entity
@Table(name = "handover_item", indexes = {
        @Index(name = "idx_item_order", columnList = "orderId"),
        @Index(name = "idx_item_bag", columnList = "bagId")
})
public class HandoverItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DrugCategory category;

    @Column(nullable = false)
    private double weightKg;

    /** 环保公司是否签收，null 表示未确认 */
    private Boolean accepted;

    @Column(length = 256)
    private String rejectReason;

    /** 交接时该袋隐私包装处理状态快照（防止个人信息随药品外流，交接批次留痕） */
    @Column(length = 16)
    private String privacyStatus;

    /** 隐私处理记录号，便于从交接批次反查处置与复核留痕 */
    @Column(length = 32)
    private String privacyTreatmentNo;
}
