package com.community.medrecycle;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.*;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 隐私包装处理：
 * 现场标记+撕除/遮盖+处理后照片；居民拒绝则封袋但积分暂缓，社区管理员复核后
 * 决定 接受补发 / 不合格重新联系居民补交 / 按无积分回收；记录进入环保交接批次。
 */
@SpringBootTest
class PrivacyPackagingIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired ReviewService reviewService;
    @Autowired PrivacyService privacyService;
    @Autowired ExceptionService exceptionService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired PointsService pointsService;
    @Autowired DashboardService dashboardService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired PrivacyPackagingRecordRepository privacyRepository;
    @Autowired ReviewTaskRepository reviewTaskRepository;

    private Account resident(String name) {
        return accountRepository.findByUsername(name).orElseThrow();
    }

    private RecycleBatch weighOne(Account resident, Account recycler, Long siteId,
                                 BatchService.WeighItem item) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "带个人信息包装的降压药", 2, true, false, false, true, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        return batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(item));
    }

    private BatchService.WeighItem tabletWithPrivacy(boolean consent, PrivacyMethod method, String photos) {
        return new BatchService.WeighItem(DrugCategory.TABLET, 2.0, null, "photo://bag",
                false, true, consent, method, "现场引导居民处理药盒信息", photos);
    }

    @Test
    void consentAndProcessGrantsPointsAndLeavesTrace() {
        Account resident = resident("resident1");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        RecycleBatch batch = weighOne(resident, recycler, siteId,
                tabletWithPrivacy(true, PrivacyMethod.PEEL_OFF, "photo://privacy-done"));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);

        assertTrue(bag.isPrivacyPackaging());
        assertFalse(bag.isPointsWithheld());
        assertTrue(bag.isPointsGranted());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId())); // 2kg * 10
        assertEquals(BagStatus.ACCEPTED, bag.getStatus());

        PrivacyPackagingRecord rec = privacyRepository.findByBagId(bag.getId()).orElseThrow();
        assertEquals(PrivacyStatus.PROCESSED, rec.getStatus());
        assertTrue(rec.isResidentConsent());
        assertEquals(PrivacyMethod.PEEL_OFF, rec.getMethod());
        assertEquals("photo://privacy-done", rec.getProcessedPhotoUrls());

        // 追溯含隐私处理记录
        Map<String, Object> trace = batchService.trace(batch.getBatchNo());
        assertEquals(1, ((List<?>) trace.get("privacyRecords")).size());
        Map<?, ?> bagView = (Map<?, ?>) ((List<?>) trace.get("bags")).get(0);
        assertNotNull(bagView.get("privacyRecord"));
    }

    @Test
    void consentWithoutProcessedPhotoIsRejected() {
        Account resident = resident("resident1");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        BizException e = assertThrows(BizException.class, () -> weighOne(resident, recycler, siteId,
                tabletWithPrivacy(true, PrivacyMethod.COVER, null)));
        assertEquals(400, e.getStatus());
    }

    @Test
    void refusedWithholdsPointsThenAdminApproves() {
        Account resident = resident("resident2");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        RecycleBatch batch = weighOne(resident, recycler, siteId,
                tabletWithPrivacy(false, null, null));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        assertEquals(BagStatus.PENDING_REVIEW, bag.getStatus());
        assertTrue(bag.isPointsWithheld());
        assertFalse(bag.isPointsGranted());
        assertEquals(before, pointsService.balanceOf(resident.getId()));
        assertEquals(BatchStatus.IN_REVIEW, batchService.get(batch.getBatchNo()).getStatus());

        // 看板出现待隐私复核；风险复核列表不含该任务
        Map<String, Object> dash = dashboardService.dashboard(siteId);
        assertEquals(1L, dash.get("pendingPrivacyReviews"));
        assertTrue(reviewService.list(ReviewKind.RISK, ReviewStatus.PENDING).stream()
                .noneMatch(t -> t.getBagId().equals(bag.getId())));

        List<ReviewTask> tasks = reviewService.list(ReviewKind.PRIVACY, ReviewStatus.PENDING).stream()
                .filter(t -> t.getBagId().equals(bag.getId())).toList();
        assertEquals(1, tasks.size());

        // 暂缓中的袋不能入柜
        StorageCabinet cabinet = cabinetRepository.findBySiteId(siteId).get(0);
        BizException blocked = assertThrows(BizException.class,
                () -> storageService.putBags(cabinet.getId(), List.of(bag.getBagNo())));
        assertEquals(409, blocked.getStatus());

        // 复核通过 -> 补发积分、袋验收
        privacyService.decide(admin.getId(), tasks.get(0).getTaskNo(), PrivacyDecision.APPROVE,
                "复核照片，信息已遮盖，接受回收");
        DrugBag after = bagRepository.findById(bag.getId()).orElseThrow();
        assertFalse(after.isPointsWithheld());
        assertTrue(after.isPointsGranted());
        assertEquals(BagStatus.ACCEPTED, after.getStatus());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()));
        assertEquals(PrivacyStatus.APPROVED,
                privacyRepository.findByBagId(bag.getId()).orElseThrow().getStatus());
    }

    @Test
    void reworkThenResubmitThenApprove() {
        Account resident = resident("resident2");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        RecycleBatch batch = weighOne(resident, recycler, siteId,
                tabletWithPrivacy(false, null, null));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        ReviewTask first = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).get(0);

        // 管理员认定处理不合格 -> 重新联系居民
        privacyService.decide(admin.getId(), first.getTaskNo(), PrivacyDecision.REWORK,
                "药盒上身份证号仍可见，需重新联系居民处理");
        assertEquals(PrivacyStatus.REWORK, privacyRepository.findByBagId(bag.getId()).orElseThrow().getStatus());
        assertEquals(ReviewStatus.SUPERSEDED,
                reviewTaskRepository.findByTaskNo(first.getTaskNo()).orElseThrow().getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()));

        // 非 REWORK 状态不能补交
        PrivacyPackagingRecord rec = privacyRepository.findByBagId(bag.getId()).orElseThrow();
        // 回收员重新联系居民后补交：居民同意遮盖并补拍照片
        privacyService.reworkSubmit(recycler.getId(), rec.getRecordNo(),
                new PrivacyService.OnSiteRequest(true, true, PrivacyMethod.COVER,
                        "已重新引导居民涂黑身份证号与诊断", "photo://privacy-reworked"));
        assertEquals(PrivacyStatus.PENDING_REVIEW,
                privacyRepository.findByRecordNo(rec.getRecordNo()).orElseThrow().getStatus());

        ReviewTask second = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).get(0);
        assertNotEquals(first.getTaskNo(), second.getTaskNo());
        privacyService.decide(admin.getId(), second.getTaskNo(), PrivacyDecision.APPROVE, "复核合格");
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()));
    }

    @Test
    void noPointsDecisionKeepsDrugButNeverGrantsPoints() {
        Account resident = resident("resident2");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        RecycleBatch batch = weighOne(resident, recycler, siteId,
                tabletWithPrivacy(false, null, null));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        ReviewTask task = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).get(0);

        privacyService.decide(admin.getId(), task.getTaskNo(), PrivacyDecision.NO_POINTS,
                "居民拒绝处理且不接受再次联系，药品按无积分回收");
        DrugBag after = bagRepository.findById(bag.getId()).orElseThrow();
        assertTrue(after.isPointsForfeited());
        assertFalse(after.isPointsWithheld());
        assertFalse(after.isPointsGranted());
        assertEquals(BagStatus.ACCEPTED, after.getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()));

        // 药品继续流转：入柜 -> 交接，隐私记录随交接单留痕（使用专用大柜避免共享数据干扰）
        StorageCabinet cabinet = new StorageCabinet();
        cabinet.setSiteId(siteId);
        cabinet.setName("隐私无积分回收专用柜");
        cabinet.setCapacityBags(50);
        cabinet.setCapacityWeightKg(50);
        cabinetRepository.save(cabinet);
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null, null);
        assertEquals(BagStatus.HANDED_OVER, bagRepository.findById(bag.getId()).orElseThrow().getStatus());

        Map<String, Object> detail = handoverService.detail(order.getOrderNo());
        List<?> privacyInHandover = (List<?>) detail.get("privacyRecords");
        assertEquals(1, privacyInHandover.size());
        Map<?, ?> p = (Map<?, ?>) privacyInHandover.get(0);
        assertEquals(bag.getBagNo(), p.get("bagNo"));
        assertEquals("NO_POINTS", String.valueOf(p.get("status")));
        // 交接完成仍未发积分
        assertEquals(before, pointsService.balanceOf(resident.getId()));
    }

    @Test
    void highRiskPlusPrivacyRefuseRequiresBothReviewsBeforePoints() {
        Account resident = resident("resident2");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account reviewer = accountRepository.findByUsername("reviewer1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "安定片", 1, true, false, false, true, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.PSYCHOTROPIC, 0.5, null, "photo://bag",
                        false, true, false, null, null, null)));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        assertTrue(bag.isHighRisk());
        assertTrue(bag.isPointsWithheld());

        ReviewTask riskTask = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.RISK, ReviewStatus.PENDING).get(0);
        reviewService.decide(reviewer.getId(), riskTask.getTaskNo(), true, "高风险核对无误");
        // 仅风险通过、隐私仍暂缓：袋仍待复核、积分不发
        assertEquals(BagStatus.PENDING_REVIEW, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()));

        ReviewTask privacyTask = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).get(0);
        privacyService.decide(admin.getId(), privacyTask.getTaskNo(), PrivacyDecision.APPROVE,
                "确认信息已遮盖");
        DrugBag done = bagRepository.findById(bag.getId()).orElseThrow();
        assertEquals(BagStatus.ACCEPTED, done.getStatus());
        assertTrue(done.isPointsGranted());
        assertEquals(before + 10, pointsService.balanceOf(resident.getId())); // 0.5kg * 20
    }

    @Test
    void exposedInfoExceptionTriggersPrivacyWithhold() {
        Account resident = resident("resident2");
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        int before = pointsService.balanceOf(resident.getId());

        // 普通称重已发积分，随后发现处方/个人信息泄露
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "降压药", 1, false, false, false, false, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.TABLET, 1.0, null, "photo://bag", false)));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        assertEquals(before + 10, pointsService.balanceOf(resident.getId()));

        exceptionService.raise(batch.getBatchNo(), bag.getBagNo(),
                ExceptionType.PRESCRIPTION_INFO_EXPOSED, "交接前发现药盒姓名与诊断仍清晰可见");
        DrugBag flagged = bagRepository.findById(bag.getId()).orElseThrow();
        assertTrue(flagged.isPrivacyPackaging());
        assertTrue(flagged.isPointsWithheld());
        assertFalse(flagged.isPointsGranted());
        assertEquals(before, pointsService.balanceOf(resident.getId())); // 积分扣回暂缓

        ReviewTask task = reviewTaskRepository
                .findByBagIdAndKindAndStatus(bag.getId(), ReviewKind.PRIVACY, ReviewStatus.PENDING).get(0);
        privacyService.decide(admin.getId(), task.getTaskNo(), PrivacyDecision.APPROVE,
                "已现场撕除标签并拍照复核");
        assertEquals(before + 10, pointsService.balanceOf(resident.getId())); // 复核通过补发
    }
}
