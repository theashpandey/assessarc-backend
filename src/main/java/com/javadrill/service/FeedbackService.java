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
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_EMAIL_LENGTH = 254;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    public void saveFeedback(String uid, String message, Integer rating, String type) {
        String cleanedMessage = cleanMessage(message);
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }
        String feedbackType = normalizeFeedbackType(type);
        User user = userRepository.findById(uid).orElse(null);
        Feedback fb = Feedback.builder()
                .uid(uid)
                .userEmail(user != null ? user.getEmail() : null)
                .userName(user != null ? user.getName() : null)
                .message(cleanedMessage)
                .rating(rating)
                .type(feedbackType)
                .createdAt(System.currentTimeMillis())
                .isRead(false)
                .build();
        feedbackRepository.save(fb);
        log.info("Feedback saved from user {}: type={}, rating={}", uid, feedbackType, rating);
    }

    public void saveContact(String name, String email, String message) {
        String cleanedMessage = cleanMessage(message);
        Feedback fb = Feedback.builder()
                .userName(limit(name != null ? name.trim() : "Anonymous", MAX_NAME_LENGTH))
                .userEmail(email != null && !email.isBlank() ? limit(email.trim(), MAX_EMAIL_LENGTH) : null)
                .message(cleanedMessage)
                .type("contact")
                .createdAt(System.currentTimeMillis())
                .isRead(false)
                .build();
        feedbackRepository.save(fb);
        log.info("Contact saved from {}: {}", email, cleanedMessage.substring(0, Math.min(50, cleanedMessage.length())));
    }

    private String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message cannot be empty");
        }
        return limit(message.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", " ")
                .replaceAll(" +", " ")
                .trim(), MAX_MESSAGE_LENGTH);
    }

    private String limit(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalizeFeedbackType(String type) {
        if (type == null || type.isBlank()) return "general";
        String normalized = type.trim().toLowerCase();
        return switch (normalized) {
            case "bug", "feature", "general", "dashboard" -> normalized;
            default -> "general";
        };
    }

    public List<Dto.FeedbackItem> getAllFeedback() {
        return feedbackRepository.findAllOrderByCreatedAtDesc().stream()
                .filter(f -> !"contact".equals(f.getType()))
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
