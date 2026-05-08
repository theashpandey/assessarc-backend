package com.javadrill.controller;

import com.javadrill.dto.Dto;
import com.javadrill.service.AdminAuthService;
import com.javadrill.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final AdminAuthService adminAuthService;

    /**
     * POST /api/feedback
     * Authenticated user submits feedback from dashboard
     */
    @PostMapping("/feedback")
    public ResponseEntity<Dto.SimpleResponse> submitFeedback(
            Authentication auth,
            @RequestBody Dto.FeedbackRequest req) {
        if (req == null) {
            throw new RuntimeException("Message cannot be empty");
        }
        String uid = (String) auth.getPrincipal();
        feedbackService.saveFeedback(uid, req.getMessage(), req.getRating(), req.getType());
        return ResponseEntity.ok(new Dto.SimpleResponse(true, "Feedback received. Thank you!"));
    }

    /**
     * POST /api/contact
     * Public - contact us form from home page (no auth required)
     */
    @PostMapping("/contact")
    public ResponseEntity<Dto.SimpleResponse> contactUs(@RequestBody Dto.ContactRequest req) {
        if (req == null) {
            throw new RuntimeException("Message cannot be empty");
        }
        feedbackService.saveContact(req.getName(), req.getEmail(), req.getMessage());
        return ResponseEntity.ok(new Dto.SimpleResponse(true, "Message received! We'll get back to you soon."));
    }

    /**
     * GET /api/admin/feedback - Admin view of all feedback (secured)
     */
    @GetMapping("/admin/feedback")
    public ResponseEntity<List<Dto.FeedbackItem>> getAllFeedback(
            Authentication auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(feedbackService.getAllFeedback(from, to));
    }

    /**
     * GET /api/admin/contacts - Admin view of all contacts
     */
    @GetMapping("/admin/contacts")
    public ResponseEntity<List<Dto.ContactItem>> getAllContacts(
            Authentication auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        adminAuthService.requireAdmin(auth);
        return ResponseEntity.ok(feedbackService.getAllContacts(from, to));
    }
}
