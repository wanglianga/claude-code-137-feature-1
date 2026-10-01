package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PrivacyTreatment;
import com.community.medrecycle.domain.enums.PrivacyReviewOutcome;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PrivacyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 隐私包装处理：回收员现场标记（走称重接口），社区管理员复核暂缓积分袋 */
@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService privacyService;

    /** 隐私包装复核列表（默认待处理），附隐私处理记录与封袋信息 */
    @GetMapping("/reviews")
    public List<Map<String, Object>> listReviews(@RequestParam(required = false) ReviewStatus status) {
        AuthContext.requireRole(Role.ADMIN, Role.REVIEWER);
        return privacyService.listReviews(status);
    }

    public record DecideRequest(@NotNull PrivacyReviewOutcome outcome, String note) {
    }

    /**
     * 社区管理员复核：APPROVE 处理合格补发积分；RE_CONTACT 不合格需重新联系居民；
     * NO_POINTS 按无积分回收（药品保留、积分不发）
     */
    @PostMapping("/reviews/{taskNo}/decide")
    public PrivacyTreatment decide(@PathVariable String taskNo,
                                   @Validated @RequestBody DecideRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return privacyService.decide(admin.getId(), taskNo, request.outcome(), request.note());
    }

    /** 待社区重新联系居民的隐私处理记录 */
    @GetMapping("/re-contact")
    public List<PrivacyTreatment> listReContact() {
        AuthContext.requireRole(Role.ADMIN, Role.RECYCLER);
        return privacyService.listReContact();
    }

    public record FollowupRequest(boolean residentTreated, String treatedPhotoUrls, String note) {
    }

    /** 登记重新联系居民后的处置：配合处理补拍照片则补发积分，否则转无积分回收 */
    @PostMapping("/treatments/{treatmentNo}/followup")
    public PrivacyTreatment followup(@PathVariable String treatmentNo,
                                     @Validated @RequestBody FollowupRequest request) {
        Account admin = AuthContext.requireRole(Role.ADMIN);
        return privacyService.resolveFollowup(admin.getId(), treatmentNo,
                request.residentTreated(), request.treatedPhotoUrls(), request.note());
    }

    @GetMapping("/treatments/{treatmentNo}")
    public PrivacyTreatment get(@PathVariable String treatmentNo) {
        AuthContext.require();
        return privacyService.get(treatmentNo);
    }
}
