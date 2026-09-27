package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 暂存柜 */
@Getter
@Setter
@Entity
@Table(name = "storage_cabinet")
public class StorageCabinet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private int capacityBags;

    @Column(nullable = false)
    private double capacityWeightKg;

    @Column(nullable = false)
    private int usedBags = 0;

    @Column(nullable = false)
    private double usedWeightKg = 0;

    @Version
    private long version;
}
