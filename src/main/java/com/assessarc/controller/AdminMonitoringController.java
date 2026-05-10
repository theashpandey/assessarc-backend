package com.assessarc.controller;

import com.assessarc.dto.Dto;
import com.assessarc.service.AdminAnalyticsService;
import com.assessarc.service.AdminAuthService;
import com.assessarc.service.GeminiMonitoringService;
import com.assessarc.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private final GeminiMonitoringService geminiMonitoringService;
    private final AdminAuthService adminAuthService;
    private final AdminAnalyticsService adminAnalyticsService;
    private final WalletService walletService;

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

    @GetMapping("/redeems")
    public ResponseEntity<List<Dto.RedeemRequestItem>> getRedeemRequests(Authentication auth) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(walletService.listRedeemRequests());
    }

    @PostMapping("/redeems/{id}/approve")
    public ResponseEntity<Dto.RedeemRequestItem> approveRedeem(
            Authentication auth,
            @PathVariable String id,
            @RequestBody(required = false) Dto.AdminRedeemActionRequest req) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(walletService.approveRedeem(id, req));
    }

    @PostMapping("/redeems/{id}/reject")
    public ResponseEntity<Dto.RedeemRequestItem> rejectRedeem(
            Authentication auth,
            @PathVariable String id,
            @RequestBody(required = false) Dto.AdminRedeemActionRequest req) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(walletService.rejectRedeem(id, req));
    }

    @PostMapping("/redeems/{id}/done")
    public ResponseEntity<Dto.RedeemRequestItem> markRedeemDone(
            Authentication auth,
            @PathVariable String id,
            @RequestBody(required = false) Dto.AdminRedeemActionRequest req) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(walletService.markRedeemDone(id, req));
    }
}
