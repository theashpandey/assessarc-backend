package com.javadrill.controller;

import com.javadrill.dto.Dto;
import com.javadrill.service.AdminAnalyticsService;
import com.javadrill.service.AdminAuthService;
import com.javadrill.service.GeminiMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private final GeminiMonitoringService geminiMonitoringService;
    private final AdminAuthService adminAuthService;
    private final AdminAnalyticsService adminAnalyticsService;

    @GetMapping({"/users/analytics", "/dashboard"})
    public ResponseEntity<Dto.AdminUserAnalyticsResponse> getUserAnalytics(
            Authentication auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(adminAnalyticsService.getUserAnalytics(from, to));
    }

    @GetMapping("/gemini/usage")
    public ResponseEntity<Dto.GeminiUsageReport> getGeminiUsage(
            Authentication auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String interviewId) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(geminiMonitoringService.getReport(from, to, month, userId, interviewId));
    }
}
