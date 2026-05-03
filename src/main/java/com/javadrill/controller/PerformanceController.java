package com.javadrill.controller;

import com.javadrill.dto.Dto;
import com.javadrill.service.PerformanceAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceAnalysisService analysisService;

    /**
     * GET /api/performance/analysis
     * Returns AI-generated deep interviewer-like analysis
     * based on last 7 interview sessions.
     */
    @GetMapping("/analysis")
    public ResponseEntity<Dto.PerformanceAnalysisResponse> getAnalysis(Authentication auth) {
        String uid = (String) auth.getPrincipal();
        return ResponseEntity.ok(analysisService.generateAnalysis(uid));
    }
}
