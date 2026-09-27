package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.ReviewTask;
import com.community.medrecycle.domain.enums.ReviewStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.ReviewService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public List<ReviewTask> list(@RequestParam(required = false) ReviewStatus status) {
        AuthContext.requireRole(Role.REVIEWER, Role.ADMIN);
        return reviewService.list(status);
    }

    public record DecideRequest(boolean approve, String note) {
    }

    /** 复核员审批：通过则发积分，拒绝则扣回并终止该袋流转 */
    @PostMapping("/{taskNo}/decide")
    public ReviewTask decide(@PathVariable String taskNo, @Validated @RequestBody DecideRequest request) {
        Account reviewer = AuthContext.requireRole(Role.REVIEWER, Role.ADMIN);
        return reviewService.decide(reviewer.getId(), taskNo, request.approve(), request.note());
    }
}
