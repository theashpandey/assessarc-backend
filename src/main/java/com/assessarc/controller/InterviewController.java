package com.assessarc.controller;

import com.assessarc.dto.Dto;
import com.assessarc.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    @Qualifier("interviewTaskExecutor")
    private final Executor interviewTaskExecutor;

    @PostMapping("/start")
    public CompletableFuture<ResponseEntity<Dto.StartInterviewResponse>> start(
            Authentication auth,
            @RequestBody Dto.StartInterviewRequest req) {
        if (req == null) {
            throw new RuntimeException("Interview start request is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("START interview uid={} duration={}min", uid, req.getDurationMinutes());
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.startInterview(uid, req)), interviewTaskExecutor);
    }

    @PostMapping("/submit")
    public CompletableFuture<ResponseEntity<Dto.SubmitAnswerResponse>> submit(
            Authentication auth,
            @RequestBody Dto.SubmitAnswerRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT answer uid={} interviewId={} qIndex={}",
                uid, req.getInterviewId(), req.getQuestionIndex());
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.submitAnswer(uid, req)), interviewTaskExecutor);
    }

    @PostMapping(value = "/submit-audio", consumes = "multipart/form-data")
    public CompletableFuture<ResponseEntity<Dto.SubmitAnswerResponse>> submitAudio(
            Authentication auth,
            @RequestParam String interviewId,
            @RequestParam(required = false) String questionId,
            @RequestParam(defaultValue = "0") int questionIndex,
            @RequestParam(required = false, defaultValue = "") String fallbackAnswer,
            @RequestPart("audio") MultipartFile audio) {
        if (interviewId == null || interviewId.isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT audio answer uid={} interviewId={} qIndex={} bytes={}",
                uid, interviewId, questionIndex, audio != null ? audio.getSize() : 0);
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.submitAudioAnswer(
                        uid, interviewId, questionId, questionIndex, fallbackAnswer, audio)), interviewTaskExecutor);
    }

    @PostMapping("/submit-coding")
    public CompletableFuture<ResponseEntity<Dto.SubmitCodingAnswerResponse>> submitCoding(
            Authentication auth,
            @RequestBody Dto.SubmitCodingAnswerRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("SUBMIT coding answer uid={} interviewId={} qIndex={}",
                uid, req.getInterviewId(), req.getQuestionIndex());
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.submitCodingAnswer(uid, req)), interviewTaskExecutor);
    }

    @PostMapping("/next-question")
    public CompletableFuture<ResponseEntity<Dto.NextQuestionResponse>> nextQuestion(
            Authentication auth,
            @RequestBody Dto.NextQuestionRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("NEXT question uid={} interviewId={}", uid, req.getInterviewId());
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.nextQuestion(uid, req)), interviewTaskExecutor);
    }

    /**
     * FIX: complete is now idempotent — calling it multiple times is safe.
     * Also called automatically by submit when isLastQuestion=true.
     */
    @PostMapping("/complete")
    public CompletableFuture<ResponseEntity<Dto.CompleteInterviewResponse>> complete(
            Authentication auth,
            @RequestBody Dto.CompleteInterviewRequest req) {
        if (req == null || req.getInterviewId() == null || req.getInterviewId().isBlank()) {
            throw new RuntimeException("Interview ID is required");
        }
        String uid = (String) auth.getPrincipal();
        log.info("COMPLETE interview uid={} interviewId={}", uid, req.getInterviewId());
        return CompletableFuture.supplyAsync(
                () -> ResponseEntity.ok(interviewService.completeInterview(uid, req.getInterviewId())), interviewTaskExecutor);
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
