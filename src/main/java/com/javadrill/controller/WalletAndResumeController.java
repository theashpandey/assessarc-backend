package com.javadrill.controller;

import com.javadrill.dto.Dto;
import com.javadrill.service.ResumeService;
import com.javadrill.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class WalletAndResumeController {

    private final WalletService walletService;
    private final ResumeService resumeService;

    // ── WALLET ──

    /** GET /api/wallet/balance */
    @GetMapping("/api/wallet/balance")
    public ResponseEntity<Dto.WalletBalanceResponse> balance(Authentication auth) {
        return ResponseEntity.ok(walletService.getBalance((String) auth.getPrincipal()));
    }

    /** GET /api/wallet/transactions */
    @GetMapping("/api/wallet/transactions")
    public ResponseEntity<List<Dto.WalletTransactionItem>> transactionHistory(Authentication auth) {
        return ResponseEntity.ok(walletService.getHistory((String) auth.getPrincipal()));
    }

    /** POST /api/wallet/order — Create Razorpay order */
    @PostMapping("/api/wallet/order")
    public ResponseEntity<Dto.CreateOrderResponse> createOrder(
            Authentication auth,
            @RequestBody Dto.CreateOrderRequest req) {
        if (req == null) {
            throw new RuntimeException("Credit pack is required");
        }
        return ResponseEntity.ok(walletService.createOrder(
                (String) auth.getPrincipal(), req.getCreditPack()));
    }

    /** POST /api/wallet/verify — Verify payment + credit wallet */
    @PostMapping("/api/wallet/verify")
    public ResponseEntity<Dto.VerifyPaymentResponse> verifyPayment(
            Authentication auth,
            @RequestBody Dto.VerifyPaymentRequest req) {
        if (req == null) {
            throw new RuntimeException("Payment verification details are required");
        }
        return ResponseEntity.ok(walletService.verifyPayment(
                (String) auth.getPrincipal(), req));
    }

    // ── RESUME ──

    /** POST /api/resume/upload — Upload PDF or TXT resume */
    @PostMapping(value = "/api/resume/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Dto.ResumeUploadResponse> uploadResume(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(resumeService.uploadResume(
                (String) auth.getPrincipal(), file));
    }

    /** POST /api/profile/interview-preferences — Save role and experience targeting */
    @PostMapping("/api/profile/interview-preferences")
    public ResponseEntity<Dto.InterviewPreferenceResponse> saveInterviewPreferences(
            Authentication auth,
            @RequestBody Dto.InterviewPreferenceRequest req) {
        return ResponseEntity.ok(resumeService.saveInterviewPreferences(
                (String) auth.getPrincipal(), req));
    }
}
