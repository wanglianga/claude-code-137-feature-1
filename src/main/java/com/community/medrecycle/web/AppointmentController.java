package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.Appointment;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.AppointmentService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    public record CreateRequest(
            @NotNull Long siteId,
            @NotBlank String drugTypeDesc,
            @Min(1) int estimatedQty,
            boolean hasPrescription,
            boolean hasLiquid,
            boolean hasInjection,
            boolean hasPersonalInfo,
            Long activityDayId) {
    }

    /** 居民预约回收 */
    @PostMapping
    public Appointment create(@Validated @RequestBody CreateRequest request) {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return appointmentService.create(resident.getId(), request.siteId(), request.drugTypeDesc(),
                request.estimatedQty(), request.hasPrescription(), request.hasLiquid(),
                request.hasInjection(), request.hasPersonalInfo(), request.activityDayId());
    }

    @GetMapping("/mine")
    public List<Appointment> mine() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return appointmentService.mine(resident.getId());
    }

    @GetMapping("/{appointmentNo}")
    public Appointment get(@PathVariable String appointmentNo) {
        AuthContext.require();
        return appointmentService.get(appointmentNo);
    }

    @PostMapping("/{appointmentNo}/cancel")
    public Appointment cancel(@PathVariable String appointmentNo) {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return appointmentService.cancel(resident.getId(), appointmentNo);
    }
}
