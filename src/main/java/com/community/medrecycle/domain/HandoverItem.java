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
}
