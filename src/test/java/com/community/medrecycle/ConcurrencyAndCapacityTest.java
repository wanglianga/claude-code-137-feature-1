package com.community.medrecycle;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.*;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 峰值与容量：积分兑换并发不超卖、暂存柜容量强制与预警 */
@SpringBootTest
class ConcurrencyAndCapacityTest {

    @Autowired
    PointsService pointsService;
    @Autowired
    StorageService storageService;
    @Autowired
    AppointmentService appointmentService;
    @Autowired
    BatchService batchService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    GiftRepository giftRepository;
    @Autowired
    CommunitySiteRepository siteRepository;
    @Autowired
    StorageCabinetRepository cabinetRepository;
    @Autowired
    DrugBagRepository bagRepository;

    @Test
    void exchangePeakNeverOversells() throws InterruptedException {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();

        Gift gift = new Gift();
        gift.setName("峰值测试礼品");
        gift.setCostPoints(50);
        gift.setStock(5);
        giftRepository.save(gift);

        pointsService.grant(resident.getId(), 1000, "TEST", "peak-init", "测试预置");
        int balanceBefore = pointsService.balanceOf(resident.getId());

        int threads = 20;
        var pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(1);
        var results = new ConcurrentLinkedQueue<PointsExchange>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    latch.await();
                    results.add(pointsService.exchange(resident.getId(), gift.getId()));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        if (!errors.isEmpty()) {
            fail("并发兑换出现异常: " + errors.peek(), errors.peek());
        }

        long success = results.stream().filter(e -> e.getStatus() == ExchangeStatus.SUCCESS).count();
        long failed = results.stream().filter(e -> e.getStatus() == ExchangeStatus.FAILED).count();
        assertEquals(threads, results.size());
        assertEquals(5, success, "库存 5 件只能成功 5 单");
        assertEquals(threads - 5, failed);
        assertEquals(0, giftRepository.findById(gift.getId()).orElseThrow().getStock());
        assertEquals(balanceBefore - 5 * 50, pointsService.balanceOf(resident.getId()), "余额扣减必须精确");
    }

    @Test
    void cabinetCapacityIsEnforcedAndWarned() {
        Account resident = accountRepository.findByUsername("resident1").orElseThrow();
        Account recycler = accountRepository.findByUsername("recycler1").orElseThrow();
        Long siteId = siteRepository.findAll().get(0).getId();

        // 小容量柜：2 袋 / 3kg
        StorageCabinet tiny = new StorageCabinet();
        tiny.setSiteId(siteId);
        tiny.setName("测试小柜");
        tiny.setCapacityBags(2);
        tiny.setCapacityWeightKg(3.0);
        cabinetRepository.save(tiny);

        // 三袋各 1.2kg 片剂，第三袋将超出容量
        String[] bagNos = new String[3];
        for (int i = 0; i < 3; i++) {
            Appointment appointment = appointmentService.create(resident.getId(), siteId,
                    "片剂", 1, false, false, false, false, null);
            RecycleBatch batch = batchService.checkIn(recycler.getId(), appointment.getAppointmentNo());
            batchService.weigh(recycler.getId(), batch.getBatchNo(), List.of(
                    new BatchService.WeighItem(DrugCategory.TABLET, 1.2, null, null, false)));
            bagNos[i] = bagRepository.findByBatchId(batch.getId()).get(0).getBagNo();
        }

        storageService.putBags(tiny.getId(), List.of(bagNos[0], bagNos[1]));
        StorageCabinet afterTwo = cabinetRepository.findById(tiny.getId()).orElseThrow();
        assertEquals(2, afterTwo.getUsedBags());

        // 第三袋：袋数 3>2 且重量 3.6>3.0 -> 拒绝入柜
        BizException e = assertThrows(BizException.class,
                () -> storageService.putBags(tiny.getId(), List.of(bagNos[2])));
        assertEquals(409, e.getStatus());

        // 使用率 2/2 = 100% -> FULL 预警出现在预警列表
        List<Map<String, Object>> alerts = storageService.alerts(siteId);
        assertTrue(alerts.stream().anyMatch(a -> {
            StorageCabinet c = (StorageCabinet) ((Map<?, ?>) a).get("cabinet");
            return c.getId().equals(tiny.getId()) && "FULL".equals(a.get("warning"));
        }));
    }
}
