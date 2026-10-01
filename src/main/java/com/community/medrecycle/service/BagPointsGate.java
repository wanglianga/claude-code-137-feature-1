package com.community.medrecycle.service;

import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.ReviewKind;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单袋积分发放网关：同时满足「无高风险/异常待复核」与「隐私处理已闭环（非暂缓/非无积分回收）」
 * 两个条件时才发放，任一阻塞未解除都跳过，解除方负责再次调用（幂等）。
 */
@Component
@RequiredArgsConstructor
public class BagPointsGate {

    private final DrugBagRepository bagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final PointsService pointsService;

    /** 发放单袋积分（幂等：已发放/0 分/已判无积分回收则跳过） */
    @Transactional
    public void grantBagPoints(RecycleBatch batch, DrugBag bag) {
        if (bag.isPointsGranted() || bag.getPoints() <= 0 || bag.isPointsForfeited()) {
            return;
        }
        pointsService.grant(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                "回收发放 " + bag.getCategory() + " " + bag.getWeightKg() + "kg");
        bag.setPointsGranted(true);
        bagRepository.save(bag);
    }

    /** 扣回单袋已发积分（幂等：未发放则跳过） */
    @Transactional
    public void revokeBagPoints(RecycleBatch batch, DrugBag bag, String reason) {
        if (!bag.isPointsGranted()) {
            return;
        }
        pointsService.revoke(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(), reason);
        bag.setPointsGranted(false);
        bagRepository.save(bag);
    }

    /**
     * 阻塞解除后尝试发积分：
     * 1. 隐私复核未闭环（暂缓中或判定无积分回收）-> 不发；
     * 2. 袋仍处于高风险/异常人工复核 -> 不发。
     */
    @Transactional
    public void tryUnlock(RecycleBatch batch, DrugBag bag) {
        if (bag.isPointsGranted() || bag.getPoints() <= 0 || bag.isPointsForfeited()) {
            return;
        }
        if (bag.isPointsWithheld()) {
            return;
        }
        boolean pendingRiskReview = reviewTaskRepository.findByBagId(bag.getId()).stream()
                .anyMatch(t -> t.getKind() == ReviewKind.RISK && t.getStatus() == ReviewStatus.PENDING);
        if (pendingRiskReview) {
            return;
        }
        grantBagPoints(batch, bag);
    }

    /** 是否存在待处理的风险复核任务 */
    public boolean hasPendingRiskReview(Long bagId) {
        return reviewTaskRepository.findByBagId(bagId).stream()
                .anyMatch(t -> t.getKind() == ReviewKind.RISK && t.getStatus() == ReviewStatus.PENDING);
    }
}
