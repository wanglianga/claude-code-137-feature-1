package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.StorageService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @GetMapping("/cabinets")
    public List<Map<String, Object>> cabinets(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN, Role.REVIEWER);
        return storageService.cabinets(siteId);
    }

    public record PutRequest(@NotNull Long cabinetId, @NotEmpty List<String> bagNos) {
    }

    /** 已验收封袋入暂存柜 */
    @PostMapping("/put")
    public Map<String, Object> put(@Validated @RequestBody PutRequest request) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return storageService.putBags(request.cabinetId(), request.bagNos());
    }

    /** 超期暂存药品 */
    @GetMapping("/overdue")
    public List<Map<String, Object>> overdue(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return storageService.overdue(siteId);
    }

    /** 暂存柜容量预警 */
    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.RECYCLER, Role.ADMIN);
        return storageService.alerts(siteId);
    }
}
