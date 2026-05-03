package com.javadrill.service;

import com.javadrill.dto.Dto;
import com.javadrill.model.Feedback;
import com.javadrill.model.User;
import com.javadrill.repository.FeedbackRepository;
import com.javadrill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    public void saveFeedback(String uid, String message, Integer rating, String type) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message cannot be empty");
        }
        User user = userRepository.findById(uid).orElse(null);
        Feedback fb = Feedback.builder()
                .uid(uid)
                .userEmail(user != null ? user.getEmail() : null)
                .userName(user != null ? user.getName() : null)
                .message(message.trim())
                .rating(rating)
                .type(type != null ? type : "dashboard")
                .createdAt(System.currentTimeMillis())
                .isRead(false)
                .build();
        feedbackRepository.save(fb);
        log.info("Feedback saved from user {}: type={}, rating={}", uid, type, rating);
    }

    public void saveContact(String name, String email, String message) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message cannot be empty");
        }
        Feedback fb = Feedback.builder()
                .userName(name != null ? name.trim() : "Anonymous")
                .userEmail(email != null ? email.trim() : null)
                .message(message.trim())
                .type("contact")
                .createdAt(System.currentTimeMillis())
                .isRead(false)
                .build();
        feedbackRepository.save(fb);
        log.info("Contact saved from {}: {}", email, message.substring(0, Math.min(50, message.length())));
    }

    public List<Dto.FeedbackItem> getAllFeedback() {
        return feedbackRepository.findByTypeOrderByCreatedAtDesc("dashboard").stream()
                .map(f -> Dto.FeedbackItem.builder()
                        .id(f.getId())
                        .userName(f.getUserName())
                        .userEmail(f.getUserEmail())
                        .message(f.getMessage())
                        .rating(f.getRating())
                        .type(f.getType())
                        .createdAt(f.getCreatedAt() > 0 ? FMT.format(Instant.ofEpochMilli(f.getCreatedAt())) : "")
                        .build())
                .collect(Collectors.toList());
    }

    public List<Dto.ContactItem> getAllContacts() {
        return feedbackRepository.findByTypeOrderByCreatedAtDesc("contact").stream()
                .map(f -> Dto.ContactItem.builder()
                        .id(f.getId())
                        .name(f.getUserName())
                        .email(f.getUserEmail())
                        .message(f.getMessage())
                        .createdAt(f.getCreatedAt() > 0 ? FMT.format(Instant.ofEpochMilli(f.getCreatedAt())) : "")
                        .build())
                .collect(Collectors.toList());
    }
}
