package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Firestore collection: "feedbacks"
 * Stores both dashboard feedback and contact-us submissions
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {
    private String id;
    private String uid;         // null for contact-us
    private String userEmail;
    private String userName;
    private String message;
    private Integer rating;     // 1-5 stars, null for contact
    private String type;        // "dashboard" | "contact" | "bug" | "feature"
    private long createdAt;     // epoch millis — Firestore serializes Instant inconsistently
    private boolean isRead;
}
