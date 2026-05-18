package com.assessarc.model;

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
    private String status;         // STARTED | COMPLETED | ANALYSIS_PENDING | ABANDONED
    private int durationMinutes;   // 30 or 60
    private String interviewRole;
    private String experienceLevel;
    private int creditsDeducted;
    private long startedAt;
    private long completedAt;

    private String resumeSummary;
    private List<QuestionAnswer> questions;
    private List<QuestionAnswer> questionPool;
    private int askedQuestionCount;
    private Scores scores;
    private Analysis analysis;
    private String completionMessage;
    private long analysisRetryAfter;
    private long lastAnswerUpdatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionAnswer {
        private String questionId;
        private String question;
        private String category;
        private String difficulty;
        private String type; // "text" or "coding"
        private CodingQuestionData codingData;
        private String answer;
        private String feedback;
        private long answerTimestamp;
        private AnswerTrace answerTrace;
        private CodingSubmission codingSubmission;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerTrace {
        private String source; // audio_transcription | browser_text | browser_fallback | skipped
        private String transcriptionStatus; // success | failed | not_available
        private String browserTranscript;
        private String audioTranscript;
        private String finalTranscript;
        private String audioMimeType;
        private long audioBytes;
        private long transcribedAt;
        private long correctedAt;
        private String error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodingQuestionData {
        private String language;
        private String expectedOutput;
        private List<TestCase> testCases;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCase {
        private String input;
        private String expectedOutput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodingSubmission {
        private String language;
        private String code;
        private String executionResult;
        private String aiEvaluation;
        private int score;
        private long timeTakenMs;
        private long submittedAt;
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
        private int roleDepth;
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
