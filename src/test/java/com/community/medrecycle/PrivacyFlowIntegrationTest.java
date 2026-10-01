package com.community.medrecycle;

import com.community.medrecycle.domain.*;
import com.community.medrecycle.config.BizException;
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
 * 隐私包装处理链路：
 * 现场撕除/遮盖即发积分；居民不同意 -> 封袋但积分暂缓 -> 社区管理员复核
 * （通过补发 / 重新联系居民 / 无积分回收）；处理记录随环保交接批次留痕。
 */
@SpringBootTest
class PrivacyFlowIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired BatchService batchService;
    @Autowired ReviewService reviewService;
    @Autowired PrivacyService privacyService;
    @Autowired StorageService storageService;
    @Autowired HandoverService handoverService;
    @Autowired PointsService pointsService;
    @Autowired AccountRepository accountRepository;
    @Autowired CommunitySiteRepository siteRepository;
    @Autowired StorageCabinetRepository cabinetRepository;
    @Autowired DrugBagRepository bagRepository;
    @Autowired PrivacyTreatmentRepository treatmentRepository;

    private StorageCabinet bigCabinet(Long siteId) {
        StorageCabinet cabinet = new StorageCabinet();
        cabinet.setSiteId(siteId);
        cabinet.setName("隐私测试专用柜");
        cabinet.setCapacityBags(100);
        cabinet.setCapacityWeightKg(100);
        return cabinetRepository.save(cabinet);
    }

    private record Setup(RecycleBatch batch, DrugBag bag) {
    }

    private Setup weighPrivacyBag(Account resident, Account recycler, Long siteId, double weightKg,
                                  boolean packaging, Boolean consent, String treatedPhoto) {
        Appointment appointment = appointmentService.create(resident.getId(), siteId,
                "带姓名药盒的降压药", 2, true, false, false, packaging, null);
        RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
        batch = batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                new BatchService.WeighItem(DrugCategory.TABLET, weightKg, null,
                        "photo://seal", false, packaging, consent, "TEAR_OFF", treatedPhoto,
                        packaging ? "药盒标签含姓名/身份证号/诊断" : null)));
        DrugBag bag = bagRepository.findByBatchId(batch.getId()).get(0);
        return new Setup(batch, bag);
    }

    @Test
    void treatedOnSiteGrantsPointsImmediatelyAndTravelsWithHandover() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = bigCabinet(siteId);

        int before = pointsService.balanceOf(resident.getId());
        Setup s = weighPrivacyBag(resident, recycler, siteId, 2.0, true, true, "photo://privacy-ok");

        // 现场处理合格：即时验收发积分（2kg * 10）
        assertEquals(BagStatus.ACCEPTED, s.bag().getStatus());
        assertEquals(PrivacyStatus.TREATED, s.bag().getPrivacyStatus());
        assertFalse(s.bag().isPointsWithheld());
        assertEquals(before + 20, pointsService.balanceOf(resident.getId()));
        PrivacyTreatment treatment = treatmentRepository.findByBagId(s.bag().getId()).orElseThrow();
        assertEquals(PrivacyStatus.TREATED, treatment.getStatus());
        assertEquals("photo://privacy-ok", treatment.getTreatedPhotoUrls());

        // 入柜并交接，隐私处理记录随批次留痕
        storageService.putBags(cabinet.getId(), List.of(s.bag().getBagNo()));
        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(s.bag().getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.complete(order.getOrderNo(), "绿源-张司机", "photo://ho", null);

        Map<String, Object> detail = handoverService.detail(order.getOrderNo());
        Map<?, ?> privacyResult = (Map<?, ?>) detail.get("privacyResult");
        assertEquals(1L, privacyResult.get("privacyBagCount"));
        assertEquals(Boolean.TRUE, privacyResult.get("allCleared"));

        Map<String, Object> trace = batchService.trace(s.batch().getBatchNo());
        assertNotNull(((List<?>) trace.get("privacyTreatments")).get(0));
    }

    @Test
    void residentRefusesPointsWithheldUntilAdminApproves() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = bigCabinet(siteId);

        int before = pointsService.balanceOf(resident.getId());
        Setup s = weighPrivacyBag(resident, recycler, siteId, 1.0, true, false, null);

        // 居民不同意处理：药品封袋，但积分暂缓、待社区管理员复核、不可入柜
        assertEquals(BagStatus.PENDING_REVIEW, s.bag().getStatus());
        assertEquals(PrivacyStatus.PENDING_REVIEW, s.bag().getPrivacyStatus());
        assertTrue(s.bag().isPointsWithheld());
        assertEquals(before, pointsService.balanceOf(resident.getId()));
        assertEquals(BatchStatus.IN_REVIEW, batchService.get(s.batch().getBatchNo()).getStatus());
        assertThrows(BizException.class,
                () -> storageService.putBags(cabinet.getId(), List.of(s.bag().getBagNo())));

        // 隐私复核任务进入管理员列表
        List<Map<String, Object>> pending = privacyService.listReviews(ReviewStatus.PENDING);
        Map<?, ?> row = pending.stream()
                .filter(m -> ((ReviewTask) m.get("reviewTask")).getBagId().equals(s.bag().getId()))
                .findFirst().orElseThrow();
        String taskNo = ((ReviewTask) row.get("reviewTask")).getTaskNo();

        // 通用高风险复核接口不能处理隐私任务
        assertThrows(BizException.class,
                () -> reviewService.decide(admin.getId(), taskNo, true, "不应走此接口"));

        // 管理员复核合格 -> 补发暂缓积分
        PrivacyTreatment treated = privacyService.decide(admin.getId(), taskNo,
                PrivacyReviewOutcome.APPROVE, "电话核实已自行撕除标签，照片合格");
        assertEquals(PrivacyStatus.TREATED, treated.getStatus());
        assertEquals(before + 10, pointsService.balanceOf(resident.getId()));
        assertEquals(BagStatus.ACCEPTED, bagRepository.findById(s.bag().getId()).orElseThrow().getStatus());
        assertFalse(bagRepository.findById(s.bag().getId()).orElseThrow().isPointsWithheld());
    }

    @Test
    void unqualifiedGoesReContactThenResidentTreatsGrantsPoints() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        int before = pointsService.balanceOf(resident.getId());
        // 居民现场拒绝处理 -> 积分暂缓、进入管理员复核
        Setup s = weighPrivacyBag(resident, recycler, siteId, 1.0, true, false, null);
        DrugBag bag = s.bag();
        String taskNo = privacyService.listReviews(ReviewStatus.PENDING).stream()
                .map(m -> (ReviewTask) ((Map<?, ?>) m).get("reviewTask"))
                .filter(rt -> rt.getBagId().equals(bag.getId())).findFirst().orElseThrow().getTaskNo();

        // 不合格 -> 社区重新联系居民：药品暂留、积分仍暂缓
        PrivacyTreatment reContact = privacyService.decide(admin.getId(), taskNo,
                PrivacyReviewOutcome.RE_CONTACT, "居民情绪激动拒绝处理，需网格员再次上门沟通");
        assertEquals(PrivacyStatus.RE_CONTACT, reContact.getStatus());
        assertEquals(BagStatus.PENDING_REVIEW, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
        assertEquals(before, pointsService.balanceOf(resident.getId()));
        assertFalse(privacyService.listReContact().stream()
                .noneMatch(x -> x.getId().equals(reContact.getId())));

        // 缺照片不允许登记为已处理
        assertThrows(BizException.class, () -> privacyService.resolveFollowup(
                admin.getId(), reContact.getTreatmentNo(), true, null, "已处理"));

        // 重新联系后居民配合撕除并补拍照片 -> 合格、补发积分
        PrivacyTreatment done = privacyService.resolveFollowup(admin.getId(),
                reContact.getTreatmentNo(), true, "photo://privacy-fixed", "居民已撕除标签");
        assertEquals(PrivacyStatus.TREATED, done.getStatus());
        assertEquals(before + 10, pointsService.balanceOf(resident.getId()));
        assertEquals(BagStatus.ACCEPTED, bagRepository.findById(bag.getId()).orElseThrow().getStatus());
    }

    @Test
    void noPointsRecoveryKeepsDrugButGrantsNothing() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Account admin = accountRepository.findByUsername("admin1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();
        StorageCabinet cabinet = bigCabinet(siteId);

        int before = pointsService.balanceOf(resident.getId());
        Setup s = weighPrivacyBag(resident, recycler, siteId, 1.5, true, false, null);
        String taskNo = privacyService.listReviews(ReviewStatus.PENDING).stream()
                .map(m -> (ReviewTask) ((Map<?, ?>) m).get("reviewTask"))
                .filter(rt -> rt.getBagId().equals(s.bag().getId())).findFirst().orElseThrow().getTaskNo();

        // 无法联系居民整改 -> 按无积分回收：药品保留流转，积分不发
        privacyService.decide(admin.getId(), taskNo, PrivacyReviewOutcome.NO_POINTS, "多次联系未果，按无积分回收");
        DrugBag bag = bagRepository.findById(s.bag().getId()).orElseThrow();
        assertEquals(PrivacyStatus.NO_POINTS, bag.getPrivacyStatus());
        assertEquals(BagStatus.ACCEPTED, bag.getStatus());
        assertFalse(bag.isPointsGranted());
        assertEquals(before, pointsService.balanceOf(resident.getId()));

        // 药品仍可入柜、交接，批次重量保留
        storageService.putBags(cabinet.getId(), List.of(bag.getBagNo()));
        RecycleBatch refreshed = batchService.get(s.batch().getBatchNo());
        assertEquals(BatchStatus.STORED, refreshed.getStatus());
        assertEquals(1.5, refreshed.getTotalWeightKg(), 0.001);
        assertEquals(0, refreshed.getTotalPoints());

        HandoverOrder order = handoverService.create(siteId, "绿源环保科技", "市危废处理中心",
                List.of(bag.getBagNo()), true);
        handoverService.sign(recycler.getId(), order.getOrderNo());
        handoverService.complete(order.getOrderNo(), "绿源-张司机", null, null);
        Map<?, ?> privacyResult = (Map<?, ?>) handoverService.detail(order.getOrderNo()).get("privacyResult");
        Map<?, ?> item = (Map<?, ?>) ((List<?>) privacyResult.get("items")).get(0);
        assertEquals("NO_POINTS", item.get("privacyStatus"));
        assertEquals(Boolean.TRUE, privacyResult.get("allCleared"));
    }
}
