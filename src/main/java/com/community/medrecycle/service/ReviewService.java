package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.BagStatus;
import com.community.medrecycle.domain.enums.ReviewKind;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.RecycleBatchRepository;
import com.community.medrecycle.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 人工复核：高风险药品/异常复核通过才发积分、才允许入柜；隐私包装复核见 PrivacyService */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final BagPointsGate bagPointsGate;
    private final BatchRefresher batchRefresher;

    /** 默认只列风险复核；kind=PRIVACY 时列社区管理员隐私复核任务 */
    @Transactional(readOnly = true)
    public List<ReviewTask> list(ReviewKind kind, ReviewStatus status) {
        ReviewKind k = kind == null ? ReviewKind.RISK : kind;
        if (status == null) {
            return reviewTaskRepository.findAll().stream().filter(t -> t.getKind() == k).toList();
        }
        return reviewTaskRepository.findByKindAndStatusOrderByCreatedAtAsc(k, status);
    }

    @Transactional
    public ReviewTask decide(Long reviewerId, String taskNo, boolean approve, String note) {
        ReviewTask task = reviewTaskRepository.findByTaskNo(taskNo)
                .orElseThrow(() -> BizException.notFound("复核任务不存在: " + taskNo));
        if (task.getKind() != ReviewKind.RISK) {
            throw BizException.badRequest("隐私包装复核请走隐私复核接口");
        }
        if (task.getStatus() != ReviewStatus.PENDING) {
            throw BizException.conflict("复核任务已处理: " + task.getStatus());
        }
        DrugBag bag = bagRepository.findById(task.getBagId()).orElseThrow();
        RecycleBatch batch = batchRepository.findById(task.getBatchId()).orElseThrow();

        task.setStatus(approve ? ReviewStatus.APPROVED : ReviewStatus.REJECTED);
        task.setReviewerId(reviewerId);
        task.setDecisionNote(note);
        task.setDecidedAt(LocalDateTime.now());
        reviewTaskRepository.save(task);

        if (approve) {
            if (!bag.isPointsWithheld()) {
                bag.setStatus(BagStatus.ACCEPTED);
                bagRepository.save(bag);
            }
            // 风险解除：但若隐私处理仍暂缓，袋保持待复核、积分继续不发，由隐私复核闭环后补发
            bagPointsGate.tryUnlock(batch, bag);
        } else {
            bag.setStatus(BagStatus.REVIEW_REJECTED);
            bagRepository.save(bag);
            // 已发积分被复核否决 -> 扣回
            bagPointsGate.revokeBagPoints(batch, bag, "复核拒绝扣回: " + note);
            // 终止该袋流转，其隐私复核任务随之作废
            reviewTaskRepository.findByBagIdAndKindAndStatus(
                            bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING)
                    .forEach(t -> t.setStatus(ReviewStatus.SUPERSEDED));
        }

        batchRefresher.refresh(batch.getId());
        return task;
    }
}
