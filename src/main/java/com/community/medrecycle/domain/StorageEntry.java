package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 暂存记录：袋入柜/出柜 */
@Getter
@Setter
@Entity
@Table(name = "storage_entry", indexes = {
        @Index(name = "idx_entry_bag", columnList = "bagId"),
        @Index(name = "idx_entry_open", columnList = "siteId,outAt")
})
public class StorageEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cabinetId;

    @Column(nullable = false)
    private Long bagId;

    @Column(nullable = false, length = 32)
    private String bagNo;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false)
    private LocalDateTime inAt = LocalDateTime.now();

    private LocalDateTime outAt;
}
