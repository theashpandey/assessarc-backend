package com.assessarc.dto;

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
        private int purchasedCredits;
        private int bonusCredits;
        private boolean hasResume;
        private String interviewRole;
        private String experienceLevel;
        private boolean isNewUser;
        private int totalInterviews;
        private double avgScore;
        private String referralCode;
        private boolean isAdmin;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserProfileResponse {
        private String uid;
        private String name;
        private String email;
        private String photoUrl;
        private int walletCredits;
        private int purchasedCredits;
        private int bonusCredits;
        private boolean hasResume;
        private String resumeFileName;
        private long resumeUploadedAt;
        private String interviewRole;
        private String experienceLevel;
        private long createdAt;
        private int totalInterviews;
        private double avgScore;
        private int bestScore;
        private String referralCode;
        private String referredBy;
        private boolean isAdmin;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LoginRequest {
        private String referralCode;
        private String name;
    }

    // ── Resume ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResumeUploadResponse {
        private boolean success;
        private int charCount;
        private String fileName;
        private String resumeSummary;
        private List<String> resumeCategories;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewPreferenceRequest {
        private String interviewRole;
        private String experienceLevel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewPreferenceResponse {
        private boolean success;
        private String interviewRole;
        private String experienceLevel;
        private String message;
    }

    // ── Wallet ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WalletBalanceResponse {
        private int credits;
        private int purchasedCredits;
        private int bonusCredits;
        private int totalCredits;
        private int redeemableBalance;
        private String upiId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WalletTransactionItem {
        private String id;
        private String type;
        private int amount;
        private int balanceBefore;
        private int balanceAfter;
        private int purchasedBefore;
        private int purchasedAfter;
        private int bonusBefore;
        private int bonusAfter;
        private int purchasedDelta;
        private int bonusDelta;
        private String description;
        private String razorpayOrderId;
        private String razorpayPaymentId;
        private String interviewId;
        private String redeemRequestId;
        private long createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateOrderRequest {
        private int creditPack; // total credits in selected plan
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
        private int purchasedCredits;
        private int bonusCredits;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SaveUpiRequest {
        private String upiId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RedeemRequestDto {
        private int amount;
        private String upiId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RedeemResponse {
        private boolean success;
        private String requestId;
        private String status;
        private int purchasedCredits;
        private int bonusCredits;
        private int totalCredits;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RedeemRequestItem {
        private String id;
        private String uid;
        private String userEmail;
        private String upiId;
        private int amount;
        private String status;
        private String payoutId;
        private String adminNote;
        private long createdAt;
        private long updatedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminRedeemActionRequest {
        private String adminNote;
        private String payoutId;
    }

    // ── Interview ──
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StartInterviewRequest {
        private int durationMinutes; // 30 or 60
        private String interviewRole;
        private String experienceLevel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StartInterviewResponse {
        private String interviewId;
        private String resumeSummary;
        private String interviewRole;
        private String experienceLevel;
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
        private String type; // "text" or "coding"
        private CodingQuestionData codingData;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CodingQuestionData {
        private String language;
        private String expectedOutput;
        private List<TestCase> testCases;
        private String description;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestCase {
        private String input;
        private String expectedOutput;
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
    public static class SubmitCodingAnswerRequest {
        private String interviewId;
        private String questionId;
        private String code;
        private String language;
        private long timeTakenMs;
        private int questionIndex;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SubmitCodingAnswerResponse {
        private String executionResult;
        private String aiEvaluation;
        private int score;
        private boolean isCorrect;
        private String feedback;
        private boolean isLastQuestion;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NextQuestionRequest {
        private String interviewId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NextQuestionResponse {
        private QuestionDto question;
        private int questionIndex;
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
        private String status;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ScoresDto {
        private int overall;
        private int technical;
        private int communication;
        private int problemSolving;
        private int roleDepth;
        private Map<String, Integer> categories;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewHistoryItem {
        private String id;
        private String date;
        private String status;
        private String message;
        private String interviewRole;
        private String experienceLevel;
        private int durationMinutes;
        private ScoresDto scores;
        private int questionCount;
        private List<String> categories;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewDetailResponse {
        private String id;
        private String date;
        private String status;
        private String message;
        private String interviewRole;
        private String experienceLevel;
        private int durationMinutes;
        private ScoresDto scores;
        private List<QADetailDto> questions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QADetailDto {
        private String question;
        private String category;
        private String difficulty;
        private String type;
        private String answer;
        private String feedback;
        private CodingSubmissionDetail codingSubmission;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CodingSubmissionDetail {
        private String language;
        private String code;
        private String executionResult;
        private String aiEvaluation;
        private int score;
        private long timeTakenMs;
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

    // Gemini Admin Monitoring
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GeminiUsageReport {
        private String from;
        private String to;
        private GeminiUsageTotals total;
        private List<GeminiUsageBucket> byDay;
        private List<GeminiUsageBucket> byMonth;
        private List<GeminiUsageBucket> byUser;
        private List<GeminiUsageBucket> byInterview;
        private List<GeminiUsageBucket> byCallType;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GeminiUsageTotals {
        private int requestCount;
        private int successCount;
        private int failedCount;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GeminiUsageBucket {
        private String key;
        private GeminiUsageTotals totals;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GeminiUsageItem {
        private String id;
        private String userId;
        private String interviewId;
        private String callType;
        private String status;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private long createdAt;
        private String day;
        private String month;
        private String errorMessage;
    }

    // Admin Dashboard
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminUserAnalyticsResponse {
        private String from;
        private String to;
        private AdminUserStats stats;
        private List<AdminMetricBucket> usersByDay;
        private List<AdminMetricBucket> interviewsByDay;
        private List<AdminMetricBucket> interviewsByStatus;
        private List<AdminMetricBucket> usersByExperienceLevel;
        private List<AdminMetricBucket> interviewsByRole;
        private List<AdminUserItem> recentUsers;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminUserStats {
        private int totalUsers;
        private int usersInRange;
        private int newUsersToday;
        private int dailyActiveUsers;
        private int activeUsersInRange;
        private int usersWithResume;
        private int totalInterviews;
        private int interviewsInRange;
        private int interviewsToday;
        private int completedInterviews;
        private int pendingInterviews;
        private int startedInterviews;
        private int totalCreditsInWallets;
        private int avgScore;
        private int bestScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminMetricBucket {
        private String key;
        private int count;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminUserItem {
        private String uid;
        private String name;
        private String email;
        private int walletCredits;
        private boolean hasResume;
        private int totalInterviews;
        private double avgScore;
        private String createdAt;
        private String lastActiveAt;
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
        private String skillProfileSummary;
        private String nextInterviewGoal;
        private List<String> practiceDrills;
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
