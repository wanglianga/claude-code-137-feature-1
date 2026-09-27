package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.PointsExchange;
import com.community.medrecycle.domain.PointsTransaction;
import com.community.medrecycle.domain.enums.ExchangeStatus;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.PointsService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;

    @GetMapping("/balance")
    public Map<String, Object> balance() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return Map.of("residentId", resident.getId(), "balance", pointsService.balanceOf(resident.getId()));
    }

    @GetMapping("/transactions")
    public List<PointsTransaction> transactions() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.transactionsOf(resident.getId());
    }

    public record ExchangeRequest(@NotNull Long giftId) {
    }

    /** 积分兑换：失败（余额/库存不足）返回 409 与失败单，便于异常跟踪 */
    @PostMapping("/exchange")
    public ResponseEntity<PointsExchange> exchange(@Validated @RequestBody ExchangeRequest request) {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        PointsExchange exchange = pointsService.exchange(resident.getId(), request.giftId());
        if (exchange.getStatus() == ExchangeStatus.SUCCESS) {
            return ResponseEntity.ok(exchange);
        }
        return ResponseEntity.status(409).body(exchange);
    }

    @GetMapping("/exchanges")
    public List<PointsExchange> exchanges() {
        Account resident = AuthContext.requireRole(Role.RESIDENT);
        return pointsService.exchangesOf(resident.getId());
    }
}
