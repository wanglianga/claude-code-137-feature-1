package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** 回收批次：扫码登记、分类称重封袋、全链路追溯 */
@Service
@RequiredArgsConstructor
public class BatchService {

    private final RecycleBatchRepository batchRepository;
    private final AppointmentRepository appointmentRepository;
    private final ActivityDayRepository activityDayRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final DrugBagRepository bagRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final StorageEntryRepository storageEntryRepository;
    private final HandoverItemRepository handoverItemRepository;
    private final HandoverOrderRepository handoverOrderRepository;
    private final ExceptionRecordRepository exceptionRecordRepository;
    private final PointsTransactionRepository pointsTransactionRepository;
    private final PrivacyTreatmentRepository privacyTreatmentRepository;
    private final PointsRuleService pointsRuleService;
    private final PointsService pointsService;
    private final BatchRefresher batchRefresher;

    /** 回收员现场扫码登记：预约 -> 批次 */
    @Transactional
    public RecycleBatch checkIn(Long recyclerId, String appointmentNo) {
        Appointment appointment = appointmentRepository.findByAppointmentNo(appointmentNo)
                .orElseThrow(() -> BizException.notFound("预约不存在: " + appointmentNo));
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw BizException.conflict("预约状态不可登记: " + appointment.getStatus());
        }

        double multiplier = 1.0;
        if (appointment.getActivityDayId() != null) {
            multiplier = activityDayRepository.findById(appointment.getActivityDayId())
                    .map(ActivityDay::getPointsMultiplier).orElse(1.0);
        }

        RecycleBatch batch = new RecycleBatch();
        batch.setBatchNo(NoGen.next("BN"));
        batch.setAppointmentId(appointment.getId());
        batch.setAppointmentNo(appointment.getAppointmentNo());
        batch.setResidentId(appointment.getResidentId());
        batch.setSiteId(appointment.getSiteId());
        batch.setRecyclerId(recyclerId);
        batch.setActivityDayId(appointment.getActivityDayId());
        batch.setPointsMultiplier(multiplier);
        batch.setStatus(BatchStatus.CHECKED_IN);
        RecycleBatch saved = batchRepository.save(batch);

        appointment.setStatus(AppointmentStatus.CHECKED_IN);
        appointment.setCheckedInAt(LocalDateTime.now());
        appointmentRepository.save(appointment);
        return saved;
    }

    public record WeighItem(DrugCategory category, double weightKg, String sealNo,
                            String photoUrls, boolean dispute,
                            boolean personalInfoPackaging, Boolean residentConsent,
                            String privacyAction, String treatedPhotoUrls, String privacyNote) {

        /** 无隐私包装场景的简构造器 */
        public WeighItem(DrugCategory category, double weightKg, String sealNo,
                         String photoUrls, boolean dispute) {
            this(category, weightKg, sealNo, photoUrls, dispute, false, null, null, null, null);
        }
    }

    /** 现场分类称重封袋：生成称重记录与封袋，高风险品类自动进入人工复核 */
    @Transactional
    public RecycleBatch weigh(Long operatorId, String batchNo, List<WeighItem> items) {
        RecycleBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        if (batch.getStatus() != BatchStatus.CHECKED_IN && batch.getStatus() != BatchStatus.WEIGHING
                && batch.getStatus() != BatchStatus.IN_REVIEW) {
            throw BizException.conflict("当前批次状态不可称重: " + batch.getStatus());
        }
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("称重记录不能为空");
        }
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();

        for (WeighItem item : items) {
            if (item.category() == null) {
                throw BizException.badRequest("药品分类不能为空");
            }
            if (item.weightKg() <= 0) {
                throw BizException.badRequest("称重必须大于 0");
            }

            WeighRecord record = new WeighRecord();
            record.setBatchId(batch.getId());
            record.setCategory(item.category());
            record.setWeightKg(item.weightKg());
            record.setPhotoUrls(item.photoUrls());
            record.setDispute(item.dispute());
            record.setWeighedBy(operatorId);
            weighRecordRepository.save(record);

            DrugBag bag = new DrugBag();
            bag.setBagNo(NoGen.next("BAG"));
            bag.setBatchId(batch.getId());
            bag.setSiteId(batch.getSiteId());
            bag.setCategory(item.category());
            bag.setWeightKg(item.weightKg());
            String sealNo = (item.sealNo() == null || item.sealNo().isBlank())
                    ? NoGen.next("SEAL") : item.sealNo();
            if (bagRepository.findBySealNo(sealNo).isPresent()) {
                throw BizException.conflict("封袋编号重复: " + sealNo);
            }
            bag.setSealNo(sealNo);
            bag.setPhotoUrls(item.photoUrls());
            boolean highRisk = pointsRuleService.isHighRisk(item.category());
            bag.setHighRisk(highRisk);
            bag.setPoints(pointsRuleService.pointsFor(
                    appointment.getPointsRuleSnapshot(), item.category(), item.weightKg(), batch.getPointsMultiplier()));
            bagRepository.save(bag);

            // 隐私包装：回收员现场标记，引导居民撕除/遮盖并拍摄处理后照片
            PrivacyTreatment privacy = null;
            boolean needPrivacyReview = false;
            if (item.personalInfoPackaging()) {
                boolean consent = Boolean.TRUE.equals(item.residentConsent());
                boolean hasTreatedPhoto = item.treatedPhotoUrls() != null && !item.treatedPhotoUrls().isBlank();
                boolean treated = consent && hasTreatedPhoto;

                privacy = new PrivacyTreatment();
                privacy.setTreatmentNo(NoGen.next("PR"));
                privacy.setBatchId(batch.getId());
                privacy.setBatchNo(batch.getBatchNo());
                privacy.setBagId(bag.getId());
                privacy.setBagNo(bag.getBagNo());
                privacy.setSiteId(batch.getSiteId());
                privacy.setPersonalInfoPresent(true);
                privacy.setGuidedBy(operatorId);
                privacy.setResidentConsent(item.residentConsent());
                privacy.setAction(consent
                        ? ((item.privacyAction() == null || item.privacyAction().isBlank())
                                ? "TEAR_OFF" : item.privacyAction())
                        : "REFUSED");
                privacy.setTreatedPhotoUrls(item.treatedPhotoUrls());
                privacy.setNote(item.privacyNote());
                privacy.setPendingPoints(bag.getPoints());
                privacy.setStatus(treated ? PrivacyStatus.TREATED : PrivacyStatus.PENDING_REVIEW);
                privacy = privacyTreatmentRepository.save(privacy);
                needPrivacyReview = !treated;
            }

            applyIntakeGates(batch, bag, highRisk, privacy, needPrivacyReview);

            if (item.dispute()) {
                raiseAutoException(batch, bag, ExceptionType.WEIGHT_DISPUTE,
                        "现场称重存在争议，品类 " + item.category() + "，登记重量 " + item.weightKg() + "kg");
            }
        }

        batch.setStatus(BatchStatus.WEIGHING);
        batchRepository.save(batch);
        return batchRefresher.refresh(batch.getId());
    }

    /** 称重完成：有待复核袋 -> IN_REVIEW，否则 -> POINTS_GRANTED */
    @Transactional
    public RecycleBatch finishWeighing(String batchNo) {
        RecycleBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
        if (bagRepository.findByBatchId(batch.getId()).isEmpty()) {
            throw BizException.conflict("批次尚无称重封袋记录");
        }
        return batchRefresher.refresh(batch.getId());
    }

    void createReviewTask(RecycleBatch batch, DrugBag bag, String reason) {
        createReviewTask(batch, bag, reason, ReviewKind.HIGH_RISK.name());
    }

    void createReviewTask(RecycleBatch batch, DrugBag bag, String reason, String reviewKind) {
        ReviewTask task = new ReviewTask();
        task.setTaskNo(NoGen.next("RT"));
        task.setBatchId(batch.getId());
        task.setBagId(bag.getId());
        task.setBagNo(bag.getBagNo());
        task.setReason(reason);
        task.setReviewKind(reviewKind);
        reviewTaskRepository.save(task);
    }

    /**
     * 称重入库闸门：高风险品类需复核员复核；隐私包装未现场处理合格需社区管理员复核。
     * 任一闸门未过：袋进入待复核、积分暂缓；全部通过：验收并即时发放积分。
     */
    private void applyIntakeGates(RecycleBatch batch, DrugBag bag, boolean highRisk,
                                  PrivacyTreatment privacy, boolean needPrivacyReview) {
        boolean privacyGate = needPrivacyReview;
        if (privacy != null) {
            bag.setPrivacyTreatmentId(privacy.getId());
            bag.setPrivacyStatus(privacy.getStatus());
        }
        boolean withholdPoints = highRisk || privacyGate;

        if (privacyGate) {
            // 居民不同意处理/处理存疑：药品可封袋，但积分暂缓，社区管理员复核后决定
            bag.setPointsWithheld(true);
            bag.setPrivacyStatus(PrivacyStatus.PENDING_REVIEW);
        }

        if (withholdPoints) {
            bag.setStatus(BagStatus.PENDING_REVIEW);
            bagRepository.save(bag);
            if (highRisk) {
                createReviewTask(batch, bag, "HIGH_RISK_CATEGORY");
            }
            if (privacyGate) {
                createReviewTask(batch, bag, "PRIVACY_PENDING", ReviewKind.PRIVACY.name());
            }
        } else {
            if (privacy != null) {
                bag.setPointsWithheld(false);
            }
            bag.setStatus(BagStatus.ACCEPTED);
            bagRepository.save(bag);
            grantBagPoints(batch, bag);
        }
    }

    /** 发放单袋积分（幂等：已发放则跳过） */
    void grantBagPoints(RecycleBatch batch, DrugBag bag) {
        if (bag.isPointsGranted() || bag.isPointsWithheld() || bag.getPoints() <= 0) {
            return;
        }
        pointsService.grant(batch.getResidentId(), bag.getPoints(), "BAG", bag.getBagNo(),
                "回收发放 " + bag.getCategory() + " " + bag.getWeightKg() + "kg");
        bag.setPointsGranted(true);
        bagRepository.save(bag);
    }

    /**
     * 任一复核闸门（高风险/隐私包装）出结果后重新评估袋状态：
     * 仍有未决复核任务 -> 保持待复核；全部解除 -> 验收，隐私按无积分回收者不发积分，其余补发。
     */
    void settleAfterReview(RecycleBatch batch, DrugBag bag) {
        boolean stillPending = !reviewTaskRepository.findByBagIdAndStatus(bag.getId(), ReviewStatus.PENDING).isEmpty();
        // 隐私处理仍待复核或待重新联系居民，同样是未解除闸门：药品暂留、积分不发
        boolean privacyHold = bag.getPrivacyStatus() == PrivacyStatus.PENDING_REVIEW
                || bag.getPrivacyStatus() == PrivacyStatus.RE_CONTACT;
        if (stillPending || privacyHold) {
            bag.setStatus(BagStatus.PENDING_REVIEW);
            bagRepository.save(bag);
            batchRefresher.refresh(batch.getId());
            return;
        }

        bag.setStatus(BagStatus.ACCEPTED);
        // 隐私按无积分回收：药品保留流转，但积分不予发放
        if (bag.getPrivacyStatus() == PrivacyStatus.NO_POINTS) {
            bag.setPointsWithheld(false);
            bagRepository.save(bag);
        } else {
            bag.setPointsWithheld(false);
            bagRepository.save(bag);
            grantBagPoints(batch, bag);
        }
        batchRefresher.refresh(batch.getId());
    }

    void raiseAutoException(RecycleBatch batch, DrugBag bag, ExceptionType type, String description) {
        ExceptionRecord record = new ExceptionRecord();
        record.setExceptionNo(NoGen.next("EX"));
        record.setBatchId(batch.getId());
        record.setBatchNo(batch.getBatchNo());
        record.setBagId(bag != null ? bag.getId() : null);
        record.setBagNo(bag != null ? bag.getBagNo() : null);
        record.setType(type);
        record.setDescription(description);
        exceptionRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public RecycleBatch get(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BizException.notFound("批次不存在: " + batchNo));
    }

    @Transactional(readOnly = true)
    public List<RecycleBatch> listByResident(Long residentId) {
        return batchRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    /** 批次全链路追溯：预约 -> 称重 -> 封袋 -> 复核 -> 暂存 -> 交接 -> 积分 -> 异常 */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String batchNo) {
        RecycleBatch batch = get(batchNo);
        Appointment appointment = appointmentRepository.findById(batch.getAppointmentId()).orElseThrow();
        List<DrugBag> bags = bagRepository.findByBatchId(batch.getId());

        List<Map<String, Object>> bagViews = new ArrayList<>();
        for (DrugBag bag : bags) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("bag", bag);
            view.put("reviewTasks", reviewTaskRepository.findByBagId(bag.getId()));
            view.put("storageEntry", storageEntryRepository.findByBagIdAndOutAtIsNull(bag.getId()).orElse(null));
            List<HandoverItem> items = handoverItemRepository.findByBagId(bag.getId());
            List<Map<String, Object>> handovers = new ArrayList<>();
            for (HandoverItem item : items) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("item", item);
                handoverOrderRepository.findById(item.getOrderId()).ifPresent(o -> h.put("order", o));
                handovers.add(h);
            }
            view.put("handovers", handovers);
            view.put("pointsTransactions", pointsTransactionRepository.findByRefTypeAndRefId("BAG", bag.getBagNo()));
            view.put("privacyTreatment", privacyTreatmentRepository.findByBagId(bag.getId()).orElse(null));
            bagViews.add(view);
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("appointment", appointment);
        trace.put("batch", batch);
        trace.put("weighRecords", weighRecordRepository.findByBatchIdOrderByCreatedAtAsc(batch.getId()));
        trace.put("bags", bagViews);
        trace.put("privacyTreatments", privacyTreatmentRepository.findByBatchId(batch.getId()));
        trace.put("exceptions", exceptionRecordRepository.findByBatchId(batch.getId()));
        return trace;
    }
}
