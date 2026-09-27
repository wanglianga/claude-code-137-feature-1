package com.community.medrecycle.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 居民积分账户 */
@Getter
@Setter
@Entity
@Table(name = "points_account", uniqueConstraints = @UniqueConstraint(columnNames = "residentId"))
public class PointsAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private int balance = 0;

    @Version
    private long version;
}
