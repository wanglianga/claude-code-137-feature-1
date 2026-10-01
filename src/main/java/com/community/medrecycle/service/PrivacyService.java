package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.DrugBag;
import com.community.medrecycle.domain.PrivacyPackagingRecord;
import com.community.medrecycle.domain.RecycleBatch;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.DrugBagRepository;
import com.community.medrecycle.repository.PrivacyPackagingRecordRepository;
import com.community.medrecycle.repository.RecycleBatchRepository;
import com.community.medrecycle.repository.ReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 隐私包装处理：
 * 现场标记 -> 引导居民撕除/遮盖姓名、身份证号、诊断信息 -> 拍摄处理后照片；
 * 居民不同意处理则药品封袋但积分暂缓，生成社区管理员复核任务；
 * 复核通过才发积分，不合格则重新联系居民重新处理，或按无积分回收。
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    private final PrivacyPackagingRecordRepository privacyRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final DrugBagRepository bagRepository;
    private final RecycleBatchRepository batchRepository;
    private final BagPointsGate bagPointsGate;
    private final BatchRefresher batchRefresher;

    /** 称重封袋现场的隐私处理入参 */
    public record OnSiteRequest(boolean packaging, boolean residentConsent,
                                PrivacyMethod method, String guidanceNote, String processedPhotoUrls) {
    }

    /**
     * 回收员现场处理隐私包装并留痕。
     * 居民同意：记录撕除/遮盖方式与处理后照片；
     * 居民拒绝：记录在案、积分暂缓、袋转待复核并生成社区管理员复核任务。
     * 返回记录（未标记隐私包装时返回 null）。
     */
    @Transactional
    public PrivacyPackagingRecord markOnSite(RecycleBatch batch, DrugBag bag, Long recyclerId, OnSiteRequest req) {
        if (req == null || !req.packaging()) {
            return null;
        }
        if (req.residentConsent() && (req.method() == null
                || req.processedPhotoUrls() == null || req.processedPhotoUrls().isBlank())) {
            throw BizException.badRequest("居民同意隐私处理时，必须记录处理方式并上传处理后照片");
        }

        PrivacyPackagingRecord record = new PrivacyPackagingRecord();
        record.setRecordNo(NoGen.next("PR"));
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag.getId());
        record.setBagNo(bag.getBagNo());
        record.setRecyclerId(recyclerId);
        record.setGuidanceNote(req.guidanceNote());
        record.setResidentConsent(req.residentConsent());

        bag.setPrivacyPackaging(true);

        if (req.residentConsent()) {
            record.setMethod(req.method());
            record.setProcessedPhotoUrls(req.processedPhotoUrls());
            record.setStatus(PrivacyStatus.PROCESSED);
            return privacyRepository.save(record);
        }

        // 居民不同意现场处理：药品可封袋，但积分暂缓，社区管理员复核后决定是否接受
        record.setStatus(PrivacyStatus.PENDING_REVIEW);
        if (req.method() != null) {
            record.setMethod(req.method());
        }
        if (req.processedPhotoUrls() != null && !req.processedPhotoUrls().isBlank()) {
            record.setProcessedPhotoUrls(req.processedPhotoUrls());
        }
        PrivacyPackagingRecord saved = privacyRepository.save(record);

        bag.setPointsWithheld(true);
        bag.setStatus(BagStatus.PENDING_REVIEW);
        bagRepository.save(bag);
        createPrivacyReviewTask(batch, bag, "PRIVACY_PENDING");
        return saved;
    }

    /** 现场之外发现处方/个人信息泄露（异常联动）：暂缓积分并交社区管理员复核 */
    @Transactional
    public void flagExposed(RecycleBatch batch, DrugBag bag, String reason) {
        bag.setPrivacyPackaging(true);
        if (bag.isPointsGranted()) {
            bagPointsGate.revokeBagPoints(batch, bag, "处方/个人信息泄露，暂缓积分等待隐私复核");
        }
        bag.setPointsWithheld(true);
        if (bag.getStatus() != BagStatus.HANDED_OVER
                && bag.getStatus() != BagStatus.HANDOVER_ASSIGNED
                && bag.getStatus() != BagStatus.STORED) {
            bag.setStatus(BagStatus.PENDING_REVIEW);
        }
        bagRepository.save(bag);

        PrivacyPackagingRecord record = privacyRepository.findByBagId(bag.getId()).orElseGet(() -> {
            PrivacyPackagingRecord r = new PrivacyPackagingRecord();
            r.setRecordNo(NoGen.next("PR"));
            r.setBatchId(batch.getId());
            r.setBatchNo(batch.getBatchNo());
            r.setBagId(bag.getId());
            r.setBagNo(bag.getBagNo());
            r.setRecyclerId(batch.getRecyclerId());
            r.setResidentConsent(false);
            return r;
        });
        record.setStatus(PrivacyStatus.PENDING_REVIEW);
        privacyRepository.save(record);

        boolean alreadyPending = !reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).isEmpty();
        if (!alreadyPending) {
            createPrivacyReviewTask(batch, bag, reason);
        }
    }

    /** 社区管理员复核隐私包装 */
    @Transactional
    public ReviewTask decide(Long adminId, String taskNo, PrivacyDecision decision, String note) {
        ReviewTask task = reviewTaskRepository.findByTaskNo(taskNo)
                .orElseThrow(() -> BizException.notFound("复核任务不存在: " + taskNo));
        if (task.getKind() != ReviewKind.PRIVACY) {
            throw BizException.badRequest("该任务不是隐私包装复核任务");
        }
        if (task.getStatus() != ReviewStatus.PENDING) {
            throw BizException.conflict("复核任务已处理: " + task.getStatus());
        }
        DrugBag bag = bagRepository.findById(task.getBagId()).orElseThrow();
        RecycleBatch batch = batchRepository.findById(task.getBatchId()).orElseThrow();
        PrivacyPackagingRecord record = privacyRepository.findByBagId(bag.getId())
                .orElseThrow(() -> BizException.conflict("缺少隐私处理记录"));

        task.setReviewerId(adminId);
        task.setDecisionNote(note);
        task.setDecidedAt(LocalDateTime.now());
        record.setReviewerId(adminId);
        record.setReviewNote(note);
        record.setReviewedAt(LocalDateTime.now());

        switch (decision) {
            case APPROVE -> {
                // 复核通过：接受回收，解除积分暂缓并补发积分
                task.setStatus(ReviewStatus.APPROVED);
                record.setStatus(PrivacyStatus.APPROVED);
                bag.setPointsWithheld(false);
                bag.setPointsForfeited(false);
                bagRepository.save(bag);
                if (!bagPointsGate.hasPendingRiskReview(bag.getId())
                        && bag.getStatus() == BagStatus.PENDING_REVIEW) {
                    bag.setStatus(BagStatus.ACCEPTED);
                    bagRepository.save(bag);
                }
                bagPointsGate.tryUnlock(batch, bag);
            }
            case NO_POINTS -> {
                // 按无积分回收：药品收下继续流转，该袋积分不再发放
                task.setStatus(ReviewStatus.APPROVED);
                record.setStatus(PrivacyStatus.NO_POINTS);
                bag.setPointsWithheld(false);
                bag.setPointsForfeited(true);
                bagRepository.save(bag);
                if (!bagPointsGate.hasPendingRiskReview(bag.getId())
                        && bag.getStatus() == BagStatus.PENDING_REVIEW) {
                    bag.setStatus(BagStatus.ACCEPTED);
                    bagRepository.save(bag);
                }
                bagPointsGate.tryUnlock(batch, bag);
            }
            case REWORK -> {
                // 处理不合格：社区重新联系居民，重新撕除/遮盖后再提交；积分继续暂缓
                task.setStatus(ReviewStatus.SUPERSEDED);
                record.setStatus(PrivacyStatus.REWORK);
                privacyRepository.save(record);
            }
        }
        reviewTaskRepository.save(task);
        privacyRepository.save(record);
        batchRefresher.refresh(batch.getId());
        return task;
    }

    /** 重新联系居民后，回收员补交隐私处理（新的处理方式与处理后照片），重新进入管理员复核 */
    @Transactional
    public PrivacyPackagingRecord reworkSubmit(Long recyclerId, String recordNo, OnSiteRequest req) {
        PrivacyPackagingRecord record = privacyRepository.findByRecordNo(recordNo)
                .orElseThrow(() -> BizException.notFound("隐私处理记录不存在: " + recordNo));
        if (record.getStatus() != PrivacyStatus.REWORK) {
            throw BizException.conflict("仅复核不合格、待重新联系居民的记录可以补交处理: " + record.getStatus());
        }
        DrugBag bag = bagRepository.findById(record.getBagId()).orElseThrow();
        if (bag.getStatus() != BagStatus.PENDING_REVIEW) {
            throw BizException.conflict("封袋当前状态不可补交隐私处理: " + bag.getStatus());
        }
        if (req == null || !req.packaging()) {
            throw BizException.badRequest("补交处理必须包含隐私包装处理信息");
        }
        if (req.residentConsent() && (req.method() == null
                || req.processedPhotoUrls() == null || req.processedPhotoUrls().isBlank())) {
            throw BizException.badRequest("居民同意隐私处理时，必须记录处理方式并上传处理后照片");
        }

        record.setResidentConsent(req.residentConsent());
        record.setMethod(req.residentConsent() ? req.method() : (req.method() != null ? req.method() : record.getMethod()));
        if (req.processedPhotoUrls() != null && !req.processedPhotoUrls().isBlank()) {
            record.setProcessedPhotoUrls(req.processedPhotoUrls());
        }
        if (req.guidanceNote() != null) {
            record.setGuidanceNote(req.guidanceNote());
        }
        record.setStatus(PrivacyStatus.PENDING_REVIEW);
        record.setReviewerId(null);
        record.setReviewNote(null);
        record.setReviewedAt(null);
        privacyRepository.save(record);

        bag.setPointsWithheld(true);
        bag.setPointsForfeited(false);
        bag.setStatus(BagStatus.PENDING_REVIEW);
        bagRepository.save(bag);

        RecycleBatch batch = batchRepository.findById(bag.getBatchId()).orElseThrow();
        createPrivacyReviewTask(batch, bag, "PRIVACY_REWORK");
        batchRefresher.refresh(batch.getId());
        return record;
    }

    private void createPrivacyReviewTask(RecycleBatch batch, DrugBag bag, String reason) {
        ReviewTask task = new ReviewTask();
        task.setTaskNo(NoGen.next("RT"));
        task.setBatchId(batch.getId());
        task.setBagId(bag.getId());
        task.setBagNo(bag.getBagNo());
        task.setReason(reason);
        task.setKind(ReviewKind.PRIVACY);
        reviewTaskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public List<PrivacyPackagingRecord> list(PrivacyStatus status) {
        return status == null ? privacyRepository.findAll()
                : privacyRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public PrivacyPackagingRecord get(String recordNo) {
        return privacyRepository.findByRecordNo(recordNo)
                .orElseThrow(() -> BizException.notFound("隐私处理记录不存在: " + recordNo));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return reviewTaskRepository.countByKindAndStatus(ReviewKind.PRIVACY, ReviewStatus.PENDING);
    }
}
