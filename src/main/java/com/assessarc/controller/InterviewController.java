package com.assessarc.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assessarc.dto.Dto;
import com.assessarc.service.InterviewService;
import com.assessarc.service.SingleInterviewAnalysisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final SingleInterviewAnalysisService singleAnalysisService;
    @PostMapping("/start")
    public ResponseEntity<Dto.StartInterviewResponse> start(
            Authentication auth,
            @RequestBody Dto.StartInterviewRequest req) {
        if (req == null) {
            throw new RuntimeException("Interview start request is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("START interview uid={} duration={}min", uid, req.getDurationMinutes());
        return ResponseEntity.ok(interviewService.startInterview(uid, req));
    }

    @PostMapping("/submit")
    public ResponseEntity<Dto.SubmitAnswerResponse> submit(
            Authentication auth,
            @RequestBody Dto.SubmitAnswerRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT answer uid={} interviewId={} qIndex={}",
                uid, req.getInterviewId(), req.getQuestionIndex());
        return ResponseEntity.ok(interviewService.submitAnswer(uid, req));
    }

    @PostMapping("/submit-coding")
    public ResponseEntity<Dto.SubmitCodingAnswerResponse> submitCoding(
            Authentication auth,
            @RequestBody Dto.SubmitCodingAnswerRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT coding answer uid={} interviewId={} qIndex={}",
                uid, req.getInterviewId(), req.getQuestionIndex());
        return ResponseEntity.ok(interviewService.submitCodingAnswer(uid, req));
    }

    @PostMapping("/next-question")
    public ResponseEntity<Dto.NextQuestionResponse> nextQuestion(
            Authentication auth,
            @RequestBody Dto.NextQuestionRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("NEXT question uid={} interviewId={}", uid, req.getInterviewId());
        return ResponseEntity.ok(interviewService.nextQuestion(uid, req));
    }

    /**
     * FIX: complete is now idempotent — calling it multiple times is safe.
     * Also called automatically by submit when isLastQuestion=true.
     */
    @PostMapping("/complete")
    public ResponseEntity<Dto.CompleteInterviewResponse> complete(
            Authentication auth,
            @RequestBody Dto.CompleteInterviewRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("COMPLETE interview uid={} interviewId={}", uid, req.getInterviewId());
        return ResponseEntity.ok(interviewService.completeInterview(uid, req.getInterviewId()));
    }

    @GetMapping("/history")
    public ResponseEntity<List<Dto.InterviewHistoryItem>> history(Authentication auth) {
        String uid = (String) auth.getPrincipal();
        return ResponseEntity.ok(interviewService.getHistory(uid));
    }

    @GetMapping("/history/{id}")
    public ResponseEntity<Dto.InterviewDetailResponse> detail(
            Authentication auth,
            @PathVariable String id) {
        String uid = (String) auth.getPrincipal();
        return ResponseEntity.ok(interviewService.getDetail(uid, id));
    }

    @GetMapping("/history/{id}/analysis")
    public ResponseEntity<Dto.PerformanceAnalysisResponse> detailAnalysis(
            Authentication auth,
            @PathVariable String id) {
        String uid = (String) auth.getPrincipal();
        return ResponseEntity.ok(singleAnalysisService.getDetailAnalysis(uid, id));
    }
}
