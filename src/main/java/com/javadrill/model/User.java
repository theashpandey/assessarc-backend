package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Firestore collection: "users"
 * Document ID: Firebase UID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String uid;
    private String name;
    private String email;
    private String photoUrl;
    private int walletCredits;
    private long createdAt;
    private long lastActiveAt;

    // Resume stored as plain text
    private String resumeText;
    private String resumeFileName;
    private long resumeUploadedAt;
    private String resumeSummary; // cached AI summary

    // Interview targeting preferences
    private String interviewRole;
    private String experienceLevel;

    // Stats
    private int totalInterviews;
    private double avgScore;
    private int bestScore;

    // Referral
    private String referralCode;
    private String referredBy;
}
