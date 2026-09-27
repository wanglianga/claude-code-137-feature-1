package com.community.medrecycle.web;

import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.repository.*;
import com.community.medrecycle.service.PointsRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 基础数据目录：站点、药品分类、积分规则、礼品、活动日 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CommunitySiteRepository siteRepository;
    private final PointsRuleService pointsRuleService;
    private final GiftRepository giftRepository;
    private final ActivityDayRepository activityDayRepository;

    @GetMapping("/sites")
    public Object sites() {
        return siteRepository.findAll();
    }

    @GetMapping("/categories")
    public List<DrugCategory> categories() {
        return List.of(DrugCategory.values());
    }

    @GetMapping("/points-rules")
    public Object pointsRules() {
        return pointsRuleService.activeRules();
    }

    @GetMapping("/gifts")
    public Object gifts() {
        return giftRepository.findAll();
    }

    @GetMapping("/activity-days")
    public Object activityDays(@RequestParam(required = false) Long siteId) {
        return siteId == null ? activityDayRepository.findAll() : activityDayRepository.findBySiteId(siteId);
    }
}
