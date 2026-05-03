package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Firestore collection: "interviews"
 * Document ID: auto-generated UUID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interview {
    private String id;
    private String userId;         // Firebase UID — consistent field name everywhere
    private String status;         // STARTED | COMPLETED | ABANDONED
    private int durationMinutes;   // 30 or 60
    private int creditsDeducted;
    private long startedAt;
    private long completedAt;

    private String resumeSummary;
    private List<QuestionAnswer> questions;
    private Scores scores;
    private Analysis analysis;

    // Track per-user questions used so next session avoids repetition
    private List<String> questionIdsUsed; // bank question IDs used in this session

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionAnswer {
        private String questionId;
        private String question;
        private String category;
        private String difficulty;
        private boolean fromBank;
        private String answer;
        private String feedback;
        private long answerTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scores {
        private int overall;
        private int technical;
        private int communication;
        private int problemSolving;
        private int javaDepth;
        private Map<String, Integer> categories;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Analysis {
        private String overallAnalysis;
        private String communicationAnalysis;
        private String answeringFlowAnalysis;
        private String strengthsSummary;
        private String improvementPlan;
        private String interviewerVerdict;
        private long generatedAt;
    }
}
