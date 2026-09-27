package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.NoGen;
import com.community.medrecycle.domain.ActivityDay;
import com.community.medrecycle.domain.Appointment;
import com.community.medrecycle.domain.CommunitySite;
import com.community.medrecycle.domain.enums.AppointmentStatus;
import com.community.medrecycle.repository.ActivityDayRepository;
import com.community.medrecycle.repository.AppointmentRepository;
import com.community.medrecycle.repository.CommunitySiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final CommunitySiteRepository siteRepository;
    private final ActivityDayRepository activityDayRepository;
    private final PointsRuleService pointsRuleService;

    /** 居民预约回收：记录药品类型、数量、四类标识、社区点与积分规则快照 */
    @Transactional
    public Appointment create(Long residentId, Long siteId, String drugTypeDesc, int estimatedQty,
                              boolean hasPrescription, boolean hasLiquid, boolean hasInjection,
                              boolean hasPersonalInfo, Long activityDayId) {
        CommunitySite site = siteRepository.findById(siteId)
                .orElseThrow(() -> BizException.notFound("社区回收点不存在: " + siteId));

        ActivityDay activityDay = null;
        if (activityDayId != null) {
            activityDay = activityDayRepository.findById(activityDayId)
                    .orElseThrow(() -> BizException.notFound("活动日不存在: " + activityDayId));
            if (!activityDay.getSiteId().equals(site.getId())) {
                throw BizException.badRequest("活动日不属于该社区点");
            }
            bookActivitySlot(activityDay);
        }

        Appointment appointment = new Appointment();
        appointment.setAppointmentNo(NoGen.next("AP"));
        appointment.setResidentId(residentId);
        appointment.setSiteId(siteId);
        appointment.setActivityDayId(activityDayId);
        appointment.setDrugTypeDesc(drugTypeDesc);
        appointment.setEstimatedQty(estimatedQty);
        appointment.setHasPrescription(hasPrescription);
        appointment.setHasLiquid(hasLiquid);
        appointment.setHasInjection(hasInjection);
        appointment.setHasPersonalInfo(hasPersonalInfo);
        appointment.setPointsRuleSnapshot(pointsRuleService.snapshot());
        return appointmentRepository.save(appointment);
    }

    /** 活动日大批量回收：名额占用带乐观锁重试 */
    private void bookActivitySlot(ActivityDay activityDay) {
        int attempts = 0;
        while (true) {
            try {
                ActivityDay fresh = activityDayRepository.findById(activityDay.getId()).orElseThrow();
                if (fresh.getBookedCount() >= fresh.getMaxAppointments()) {
                    throw BizException.conflict("活动日预约名额已满");
                }
                fresh.setBookedCount(fresh.getBookedCount() + 1);
                activityDayRepository.save(fresh);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (++attempts >= 3) {
                    throw BizException.conflict("活动日预约冲突，请重试");
                }
            }
        }
    }

    @Transactional
    public Appointment cancel(Long residentId, String appointmentNo) {
        Appointment appointment = appointmentRepository.findByAppointmentNo(appointmentNo)
                .orElseThrow(() -> BizException.notFound("预约不存在: " + appointmentNo));
        if (!appointment.getResidentId().equals(residentId)) {
            throw BizException.forbidden("只能取消本人预约");
        }
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw BizException.conflict("当前状态不可取消: " + appointment.getStatus());
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        // 释放活动日名额
        if (appointment.getActivityDayId() != null) {
            activityDayRepository.findById(appointment.getActivityDayId()).ifPresent(ad -> {
                ad.setBookedCount(Math.max(0, ad.getBookedCount() - 1));
                activityDayRepository.save(ad);
            });
        }
        return appointmentRepository.save(appointment);
    }

    @Transactional(readOnly = true)
    public List<Appointment> mine(Long residentId) {
        return appointmentRepository.findByResidentIdOrderByCreatedAtDesc(residentId);
    }

    @Transactional(readOnly = true)
    public Appointment get(String appointmentNo) {
        return appointmentRepository.findByAppointmentNo(appointmentNo)
                .orElseThrow(() -> BizException.notFound("预约不存在: " + appointmentNo));
    }
}
