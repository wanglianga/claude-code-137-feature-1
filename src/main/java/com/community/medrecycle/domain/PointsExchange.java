package com.community.medrecycle.domain;

import com.community.medrecycle.domain.enums.ExchangeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 积分兑换单 */
@Getter
@Setter
@Entity
@Table(name = "points_exchange", uniqueConstraints = @UniqueConstraint(columnNames = "exchangeNo"))
public class PointsExchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String exchangeNo;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false)
    private Long giftId;

    @Column(nullable = false, length = 64)
    private String giftName;

    @Column(nullable = false)
    private int costPoints;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExchangeStatus status;

    @Column(length = 256)
    private String failReason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
