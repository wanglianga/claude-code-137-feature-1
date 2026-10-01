package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.PrivacyTreatment;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.PrivacyReviewOutcome;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import com.community.medrecycle.domain.enums.ReviewKind;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.PrivacyTreatmentRepository;
import com.community.medrecycle.repository.RecycleBatchRepository;
import com.community.medrecycle.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隐私包装复核：居民药盒保留姓名/身份证号/诊断信息，回收员现场标记隐私包装。
 * 居民不同意现场处理时药品可封袋但积分暂缓，由社区管理员复核后决定：
 * APPROVE 处理合格补发积分；RE_CONTACT 处理不合格需重新联系居民；NO_POINTS 按无积分回收。
 * 处理记录随环保交接批次留存，防止个人信息随药品外流。
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    private final PrivacyTreatmentRepository treatmentRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final BatchRefresher batchRefresher;
    private final BatchService batchService;

    /** 隐私包装复核任务（默认仅待处理），附带隐私处理记录与袋信息便于管理员研判 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReviews(ReviewStatus status) {
        List<ReviewTask> tasks = reviewTaskRepository
                .findByReviewKindAndStatusOrderByCreatedAtAsc(ReviewKind.PRIVACY.name(),
                        status == null ? ReviewStatus.PENDING : status);
        List<Map<String, Object>> views = new ArrayList<>();
        for (ReviewTask task : tasks) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("reviewTask", task);
            treatmentRepository.findByBagId(task.getBagId()).ifPresent(t -> view.put("privacyTreatment", t));
            bagRepository.findById(task.getBagId()).ifPresent(b -> view.put("bag", b));
            views.add(view);
        }
        return views;
    }

    /** 待社区重新联系居民的隐私处理记录 */
    @Transactional(readOnly = true)
    public List<PrivacyTreatment> listReContact() {
        return treatmentRepository.findByStatusOrderByCreatedAtAsc(PrivacyStatus.RE_CONTACT);
    }

    /**
     * 社区管理员复核决策：
     * APPROVE 隐私处理合格 -> 接受回收、补发暂缓积分；
     * RE_CONTACT 处理不合格 -> 社区重新联系居民，药品暂留、积分仍暂缓；
     * NO_POINTS 无法整改 -> 药品保留流转、积分不予发放。
     */
    @Transactional
    public PrivacyTreatment decide(Long adminId, String taskNo, PrivacyReviewOutcome outcome, String note) {
        if (outcome == null) {
            throw BizException.badRequest("复核结论不能为空");
        }
        ReviewTask task = reviewTaskRepository.findByTaskNo(taskNo)
                .orElseThrow(() -> BizException.notFound("复核任务不存在: " + taskNo));
        if (!ReviewKind.PRIVACY.name().equals(task.getReviewKind())) {
            throw BizException.badRequest("该复核任务不是隐私包装复核: " + task.getReason());
        }
        if (task.getStatus() != ReviewStatus.PENDING) {
            throw BizException.conflict("复核任务已处理: " + task.getStatus());
        }

        DrugBag bag = bagRepository.findById(task.getBagId()).orElseThrow();
        RecycleBatch batch = batchRepository.findById(task.getBatchId()).orElseThrow();
        PrivacyTreatment treatment = treatmentRepository.findByBagId(bag.getId())
                .orElseThrow(() -> BizException.conflict("封袋缺少隐私包装处理记录"));

        task.setReviewerId(adminId);
        task.setDecisionNote(note);
        task.setDecidedAt(LocalDateTime.now());
        treatment.setReviewerId(adminId);
        treatment.setReviewNote(note);
        treatment.setReviewedAt(LocalDateTime.now());
        treatment.setReviewOutcome(outcome);

        switch (outcome) {
            case APPROVE -> {
                task.setStatus(ReviewStatus.APPROVED);
                treatment.setStatus(PrivacyStatus.TREATED);
                bag.setPrivacyStatus(PrivacyStatus.TREATED);
                bagRepository.save(bag);
                reviewTaskRepository.save(task);
                treatmentRepository.save(treatment);
                // 全部复核闸门解除 -> 验收并补发暂缓积分
                batchService.settleAfterReview(batch, bag);
            }
            case RE_CONTACT -> {
                task.setStatus(ReviewStatus.REJECTED);
                treatment.setStatus(PrivacyStatus.RE_CONTACT);
                bag.setPrivacyStatus(PrivacyStatus.RE_CONTACT);
                // 药品暂留待重新联系居民：保持待复核、积分仍暂缓，不可入柜/交接
                bagRepository.save(bag);
                reviewTaskRepository.save(task);
                treatmentRepository.save(treatment);
                batchRefresher.refresh(batch.getId());
            }
            case NO_POINTS -> {
                task.setStatus(ReviewStatus.REJECTED);
                treatment.setStatus(PrivacyStatus.NO_POINTS);
                bag.setPrivacyStatus(PrivacyStatus.NO_POINTS);
                bagRepository.save(bag);
                reviewTaskRepository.save(task);
                treatmentRepository.save(treatment);
                // 药品按无积分回收：验收继续流转，但积分不予发放
                batchService.settleAfterReview(batch, bag);
            }
        }
        return treatment;
    }

    /**
     * 社区重新联系居民后的处置结果：
     * residentTreated=true  居民已配合撕除/遮盖并补拍处理后照片 -> 处理合格、补发积分；
     * residentTreated=false 居民仍不同意/无法整改 -> 转按无积分回收。
     */
    @Transactional
    public PrivacyTreatment resolveFollowup(Long adminId, String treatmentNo,
                                            boolean residentTreated, String treatedPhotoUrls, String note) {
        PrivacyTreatment treatment = treatmentRepository.findByTreatmentNo(treatmentNo)
                .orElseThrow(() -> BizException.notFound("隐私处理记录不存在: " + treatmentNo));
        if (treatment.getStatus() != PrivacyStatus.RE_CONTACT) {
            throw BizException.conflict("仅待重新联系居民的隐私记录可登记处置结果: " + treatment.getStatus());
        }
        DrugBag bag = bagRepository.findById(treatment.getBagId()).orElseThrow();
        RecycleBatch batch = batchRepository.findById(treatment.getBatchId()).orElseThrow();

        treatment.setReviewedAt(LocalDateTime.now());
        treatment.setReviewerId(adminId);
        treatment.setReviewNote(note);

        if (residentTreated) {
            if (treatedPhotoUrls == null || treatedPhotoUrls.isBlank()) {
                throw BizException.badRequest("居民已配合处理时必须补拍处理后照片");
            }
            treatment.setResidentConsent(true);
            treatment.setAction("TEAR_OFF");
            treatment.setTreatedPhotoUrls(treatedPhotoUrls);
            treatment.setStatus(PrivacyStatus.TREATED);
            treatment.setReviewOutcome(PrivacyReviewOutcome.APPROVE);
            bag.setPrivacyStatus(PrivacyStatus.TREATED);
            bagRepository.save(bag);
            treatmentRepository.save(treatment);
            batchService.settleAfterReview(batch, bag);
        } else {
            treatment.setStatus(PrivacyStatus.NO_POINTS);
            treatment.setReviewOutcome(PrivacyReviewOutcome.NO_POINTS);
            bag.setPrivacyStatus(PrivacyStatus.NO_POINTS);
            bagRepository.save(bag);
            treatmentRepository.save(treatment);
            batchService.settleAfterReview(batch, bag);
        }
        return treatment;
    }

    @Transactional(readOnly = true)
    public PrivacyTreatment get(String treatmentNo) {
        return treatmentRepository.findByTreatmentNo(treatmentNo)
                .orElseThrow(() -> BizException.notFound("隐私处理记录不存在: " + treatmentNo));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return reviewTaskRepository
                .findByReviewKindAndStatusOrderByCreatedAtAsc(ReviewKind.PRIVACY.name(), ReviewStatus.PENDING)
                .size();
    }

    @Transactional(readOnly = true)
    public long reContactCount() {
        return treatmentRepository.countByStatus(PrivacyStatus.RE_CONTACT);
    }
}
