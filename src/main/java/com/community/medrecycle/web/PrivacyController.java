package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PrivacyPackagingRecord;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.PrivacyDecision;
import com.community.medrecycle.domain.enums.PrivacyMethod;
import com.community.medrecycle.domain.enums.PrivacyStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PrivacyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 隐私包装处理：回收员留痕/补交，社区管理员复核决定积分发放 */
@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService privacyService;

    /** 隐私处理记录列表（按状态过滤），工作人员可查 */
    @GetMapping("/records")
    public List<PrivacyPackagingRecord> list(@RequestParam(required = false) PrivacyStatus status) {
        AuthContext.requireRole(Role.RECYCLER, Role.REVIEWER, Role.ADMIN);
        return privacyService.list(status);
    }

    @GetMapping("/records/{recordNo}")
    public PrivacyPackagingRecord get(@PathVariable String recordNo) {
        AuthContext.requireRole(Role.RECYCLER, Role.REVIEWER, Role.ADMIN);
        return privacyService.get(recordNo);
    }

    public record ReworkRequest(boolean privacyPackaging, boolean residentConsent,
                                PrivacyMethod method, String guidanceNote, String processedPhotoUrls) {
    }

    /** 复核不合格后，社区重新联系居民，回收员补交撕除/遮盖处理与新照片，再次进入复核 */
    @PostMapping("/records/{recordNo}/rework")
    public PrivacyPackagingRecord rework(@PathVariable String recordNo,
                                         @Validated @RequestBody ReworkRequest request) {
        Account recycler = AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return privacyService.reworkSubmit(recycler.getId(), recordNo,
                new PrivacyService.OnSiteRequest(request.privacyPackaging(), request.residentConsent(),
                        request.method(), request.guidanceNote(), request.processedPhotoUrls()));
    }

    public record DecideRequest(@NotNull PrivacyDecision decision, String note) {
    }

    /** 社区管理员复核：通过发积分 / 不合格重新联系居民 / 按无积分回收 */
    @PostMapping("/reviews/{taskNo}/decide")
    public ReviewTask decide(@PathVariable String taskNo, @Validated @RequestBody DecideRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return privacyService.decide(admin.getId(), taskNo, request.decision(), request.note());
    }
}
