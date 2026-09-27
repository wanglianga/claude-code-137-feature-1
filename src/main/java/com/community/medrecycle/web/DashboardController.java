package com.community.medrecycle.web;

import com.community.medrecycle.config.AuthContext;
import com.community.medrecycle.domain.enums.Role;
import com.community.medrecycle.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 管理员看板：超期暂存、双人交接判定、上门量、容量预警 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam Long siteId) {
        AuthContext.requireRole(Role.ADMIN);
        return dashboardService.dashboard(siteId);
    }
}
