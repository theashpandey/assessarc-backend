package com.javadrill.controller;

import com.google.firebase.auth.FirebaseToken;
import com.javadrill.config.AppProperties;
import com.javadrill.dto.Dto;
import com.javadrill.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final AppProperties props;

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
        feedbackService.saveFeedback(uid, req.getMessage(), req.getRating(), "dashboard");
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
    public ResponseEntity<List<Dto.FeedbackItem>> getAllFeedback(Authentication auth) {
        requireAdmin(auth);
        return ResponseEntity.ok(feedbackService.getAllFeedback());
    }

    /**
     * GET /api/admin/contacts - Admin view of all contacts
     */
    @GetMapping("/admin/contacts")
    public ResponseEntity<List<Dto.ContactItem>> getAllContacts(Authentication auth) {
        requireAdmin(auth);
        return ResponseEntity.ok(feedbackService.getAllContacts());
    }

    private void requireAdmin(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("Unauthorized");
        }

        String uid = String.valueOf(auth.getPrincipal());
        String email = "";
        if (auth.getDetails() instanceof FirebaseToken token && token.getEmail() != null) {
            email = token.getEmail().toLowerCase(Locale.ROOT);
        }

        Set<String> allowedUids = splitCsv(props.getAdmin().getAllowedUids());
        Set<String> allowedEmails = splitCsv(props.getAdmin().getAllowedEmails()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        if (!allowedUids.contains(uid) && (email.isBlank() || !allowedEmails.contains(email))) {
            throw new RuntimeException("Unauthorized");
        }
    }

    private Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }
}
