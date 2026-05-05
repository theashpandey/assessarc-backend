package com.javadrill.controller;

import com.javadrill.dto.Dto;
import com.javadrill.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @PostMapping("/start")
    public ResponseEntity<Dto.StartInterviewResponse> start(
            Authentication auth,
            @RequestBody Dto.StartInterviewRequest req) {
        String uid = (String) auth.getPrincipal();
        log.info("START interview uid={} duration={}min", uid, req.getDurationMinutes());
        return ResponseEntity.ok(interviewService.startInterview(uid, req));
    }

    @PostMapping("/submit")
    public ResponseEntity<Dto.SubmitAnswerResponse> submit(
            Authentication auth,
            @RequestBody Dto.SubmitAnswerRequest req) {
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT answer uid={} interviewId={} qIndex={}",
                uid, req.getInterviewId(), req.getQuestionIndex());
        return ResponseEntity.ok(interviewService.submitAnswer(uid, req));
    }

    @PostMapping("/next-question")
    public ResponseEntity<Dto.NextQuestionResponse> nextQuestion(
            Authentication auth,
            @RequestBody Dto.NextQuestionRequest req) {
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
        return ResponseEntity.ok(interviewService.getDetailAnalysis(uid, id));
    }
}
