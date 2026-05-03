package com.javadrill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public class Dto {

    // ── Auth ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuthResponse {
        private String uid;
        private String name;
        private String email;
        private String photoUrl;
        private int walletCredits;
        private boolean hasResume;
        private boolean isNewUser;
        private int totalInterviews;
        private double avgScore;
        private String referralCode;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserProfileResponse {
        private String uid;
        private String name;
        private String email;
        private String photoUrl;
        private int walletCredits;
        private boolean hasResume;
        private String resumeFileName;
        private long resumeUploadedAt;
        private long createdAt;
        private int totalInterviews;
        private double avgScore;
        private int bestScore;
        private String referralCode;
        private String referredBy;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LoginRequest {
        private String referralCode;
    }

    // ── Resume ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResumeUploadResponse {
        private boolean success;
        private int charCount;
        private String fileName;
        private String message;
    }

    // ── Wallet ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WalletBalanceResponse {
        private int credits;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WalletTransactionItem {
        private String id;
        private String type;
        private int amount;
        private int balanceBefore;
        private int balanceAfter;
        private String description;
        private String razorpayOrderId;
        private String razorpayPaymentId;
        private String interviewId;
        private long createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateOrderRequest {
        private int creditPack; // 10 | 25 | 50 | 100
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateOrderResponse {
        private String orderId;
        private int amount;      // in paise
        private String currency;
        private String keyId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VerifyPaymentRequest {
        private String razorpayOrderId;
        private String razorpayPaymentId;
        private String razorpaySignature;
        private int creditPack;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VerifyPaymentResponse {
        private boolean success;
        private int newBalance;
        private String message;
    }

    // ── Interview ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StartInterviewRequest {
        private int durationMinutes; // 30 or 60
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StartInterviewResponse {
        private String interviewId;
        private String resumeSummary;
        private List<QuestionDto> questions;
        private int creditsDeducted;
        private int walletBalance;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QuestionDto {
        private String id;
        private String question;
        private String category;
        private String difficulty;
        private boolean fromBank;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SubmitAnswerRequest {
        private String interviewId;
        private String questionId;
        private String answer;
        private int questionIndex;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SubmitAnswerResponse {
        private String feedback;
        private boolean isLastQuestion;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompleteInterviewRequest {
        private String interviewId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompleteInterviewResponse {
        private ScoresDto scores;
        private String interviewId;
        private long completedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ScoresDto {
        private int overall;
        private int technical;
        private int communication;
        private int problemSolving;
        private int javaDepth;
        private Map<String, Integer> categories;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewHistoryItem {
        private String id;
        private String date;
        private int durationMinutes;
        private ScoresDto scores;
        private int questionCount;
        private List<String> categories;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewDetailResponse {
        private String id;
        private String date;
        private int durationMinutes;
        private ScoresDto scores;
        private List<QADetailDto> questions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QADetailDto {
        private String question;
        private String category;
        private String difficulty;
        private String answer;
        private String feedback;
    }

    // ── Feedback / Contact ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeedbackRequest {
        private String message;
        private Integer rating; // 1-5
        private String type;    // optional: "bug" | "feature" | "general"
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContactRequest {
        private String name;
        private String email;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SimpleResponse {
        private boolean success;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeedbackItem {
        private String id;
        private String userName;
        private String userEmail;
        private String message;
        private Integer rating;
        private String type;
        private String createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContactItem {
        private String id;
        private String name;
        private String email;
        private String message;
        private String createdAt;
    }

    // ── Performance Analysis ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PerformanceAnalysisResponse {
        private String overallAnalysis;
        private String communicationAnalysis;
        private String answeringFlowAnalysis;
        private String strengthsSummary;
        private String improvementPlan;
        private String interviewerVerdict;
        private List<CategoryInsight> categoryInsights;
        private int sessionCount;
        private int avgScore;
        private int bestScore;
        private String trend; // "improving" | "declining" | "neutral"
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CategoryInsight {
        private String category;
        private int avgScore;
        private String insight;
        private String advice;
    }

    // ── Generic ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ApiError {
        private int status;
        private String error;
        private String message;
        private long timestamp;
    }
}
