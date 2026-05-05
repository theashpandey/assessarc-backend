package com.javadrill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadrill.dto.Dto;
import com.javadrill.model.Interview;
import com.javadrill.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAnalysisService {

    private final InterviewRepository interviewRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public Dto.PerformanceAnalysisResponse generateAnalysis(String uid) {
        // BUG FIX: was using "uid" field but model uses "userId"
        List<Interview> allCompleted = interviewRepository.findAllCompletedByUserId(uid);
        List<Interview> last7 = allCompleted.stream().limit(7).collect(Collectors.toList());

        if (last7.isEmpty()) {
            return Dto.PerformanceAnalysisResponse.builder()
                    .overallAnalysis("Complete at least one interview to see your personalized analysis.")
                    .communicationAnalysis("No data yet.")
                    .answeringFlowAnalysis("No data yet.")
                    .strengthsSummary("No data yet.")
                    .improvementPlan("Start your first interview to get a detailed improvement plan.")
                    .interviewerVerdict("No interviews completed yet.")
                    .categoryInsights(new ArrayList<>())
                    .sessionCount(0)
                    .avgScore(0)
                    .bestScore(0)
                    .trend("neutral")
                    .build();
        }

        // Calculate aggregate stats
        int avgScore = (int) last7.stream()
                .filter(i -> i.getScores() != null)
                .mapToInt(i -> i.getScores().getOverall())
                .average().orElse(0);
        int bestScore = last7.stream()
                .filter(i -> i.getScores() != null)
                .mapToInt(i -> i.getScores().getOverall())
                .max().orElse(0);

        // Trend: compare first half vs second half
        String trend = "neutral";
        if (last7.size() >= 3) {
            // last7[0] = most recent, last7[last] = oldest
            double recentAvg = last7.subList(0, last7.size() / 2 + 1).stream()
                    .filter(i -> i.getScores() != null)
                    .mapToInt(i -> i.getScores().getOverall()).average().orElse(0);
            double olderAvg = last7.subList(last7.size() / 2, last7.size()).stream()
                    .filter(i -> i.getScores() != null)
                    .mapToInt(i -> i.getScores().getOverall()).average().orElse(0);
            trend = recentAvg > olderAvg + 3 ? "improving" :
                    recentAvg < olderAvg - 3 ? "declining" : "neutral";
        }

        // Build rich context for Gemini
        StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATE PERFORMANCE ANALYSIS REQUEST\n");
        sb.append("Sessions analyzed: ").append(last7.size()).append("\n");
        sb.append("Average score: ").append(avgScore).append("%\n");
        sb.append("Best score: ").append(bestScore).append("%\n\n");

        for (int s = 0; s < last7.size(); s++) {
            Interview iv = last7.get(s);
            sb.append("=== SESSION ").append(s + 1)
              .append(" (").append(iv.getDurationMinutes()).append(" min) ===\n");
            sb.append("Role: ").append(geminiService.roleLabel(iv.getInterviewRole())).append("\n");
            sb.append("Experience: ").append(geminiService.experienceLabel(iv.getExperienceLevel())).append("\n");

            if (iv.getScores() != null) {
                sb.append("Scores — Overall: ").append(iv.getScores().getOverall())
                  .append("%, Technical: ").append(iv.getScores().getTechnical())
                  .append("%, Communication: ").append(iv.getScores().getCommunication())
                  .append("%, Problem Solving: ").append(iv.getScores().getProblemSolving())
                  .append("\n");
            }

            if (iv.getQuestions() != null) {
                for (Interview.QuestionAnswer q : iv.getQuestions()) {
                    String answer = q.getAnswer() != null ? q.getAnswer() : "";
                    if (answer.equals("(skipped)") || answer.isBlank()) continue;

                    sb.append("\nQ [").append(q.getCategory()).append("]: ").append(q.getQuestion()).append("\n");
                    sb.append("A: ").append(answer.substring(0, Math.min(300, answer.length())));
                    if (answer.length() > 300) sb.append("...");
                    sb.append("\n");
                    if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
                        sb.append("Interviewer feedback: ").append(q.getFeedback()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        String systemPrompt = "You are a senior interviewer with 15 years across engineering, data, product, management, architecture, and HR hiring loops. " +
                "You have just reviewed this candidate's complete interview history. " +
                "Give a brutally honest, specific, actionable analysis — like you're writing an internal hiring assessment. " +
                "DO NOT be generic. Reference specific patterns from their actual answers. " +
                "Return ONLY valid JSON, no markdown, no explanation.";

        String userPrompt = sb.toString() + """
                
                Based on the above, provide a deep analysis. Return ONLY this JSON structure:
                {
                  "overallAnalysis": "3-4 sentences about overall candidate quality. Reference specific answer patterns. Be honest.",
                  "communicationAnalysis": "2-3 sentences. Are they clear? Do they structure answers well (STAR method)? Fluent or hesitant? Give specific examples from answers.",
                  "answeringFlowAnalysis": "2-3 sentences. Do they answer directly or ramble? Do they miss key points? Strong technically but weak at explaining? Patterns noticed?",
                  "strengthsSummary": "What they're genuinely good at. Mention specific categories and what evidence shows this.",
                  "improvementPlan": "3 specific, actionable things to improve for the candidate's practiced role. Be concrete, for example a specific concept, tool, framework, hiring skill, leadership behavior, or answer structure.",
                  "interviewerVerdict": "If you were the hiring manager, what's your verdict? Strong hire / Hire with coaching / Not yet / Reject? Give reasoning.",
                  "categoryInsights": [
                    {"category": "java_core", "avgScore": 75, "insight": "Shows X pattern in answers...", "advice": "Should focus on Y specifically..."},
                    {"category": "oops", "avgScore": 80, "insight": "...", "advice": "..."}
                  ]
                }
                """;

        try {
            String raw = geminiService.callGeminiWithTemp(userPrompt, systemPrompt, 0.4);
            return parseAnalysisResponse(raw, last7.size(), avgScore, bestScore, trend);
        } catch (Exception e) {
            log.error("Analysis generation failed: {}", e.getMessage());
            return buildFallbackAnalysis(last7, avgScore, bestScore, trend);
        }
    }

    private Dto.PerformanceAnalysisResponse parseAnalysisResponse(
            String raw, int sessionCount, int avgScore, int bestScore, String trend) {
        try {
            String clean = raw.replaceAll("```json|```", "").trim();
            int start = clean.indexOf('{');
            int end = clean.lastIndexOf('}');
            if (start >= 0 && end > start) clean = clean.substring(start, end + 1);

            JsonNode root = objectMapper.readTree(clean);

            // Parse category insights
            List<Dto.CategoryInsight> insights = new ArrayList<>();
            JsonNode cats = root.path("categoryInsights");
            if (cats.isArray()) {
                for (JsonNode cat : cats) {
                    insights.add(Dto.CategoryInsight.builder()
                            .category(cat.path("category").asText())
                            .avgScore(cat.path("avgScore").asInt(0))
                            .insight(cat.path("insight").asText())
                            .advice(cat.path("advice").asText())
                            .build());
                }
            }

            return Dto.PerformanceAnalysisResponse.builder()
                    .overallAnalysis(root.path("overallAnalysis").asText())
                    .communicationAnalysis(root.path("communicationAnalysis").asText())
                    .answeringFlowAnalysis(root.path("answeringFlowAnalysis").asText())
                    .strengthsSummary(root.path("strengthsSummary").asText())
                    .improvementPlan(root.path("improvementPlan").asText())
                    .interviewerVerdict(root.path("interviewerVerdict").asText())
                    .categoryInsights(insights)
                    .sessionCount(sessionCount)
                    .avgScore(avgScore)
                    .bestScore(bestScore)
                    .trend(trend)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse analysis response: {}", e.getMessage());
            return buildFallbackAnalysis(List.of(), avgScore, bestScore, trend);
        }
    }

    private Dto.PerformanceAnalysisResponse buildFallbackAnalysis(
            List<Interview> sessions, int avgScore, int bestScore, String trend) {
        int count = sessions.size();
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis("Based on " + count + " session(s), your average score is " + avgScore + "%. "
                        + "Complete more interviews for detailed analysis.")
                .communicationAnalysis("Analysis temporarily unavailable. Please try again.")
                .answeringFlowAnalysis("Keep practicing to get detailed flow analysis.")
                .strengthsSummary("Complete more interviews to identify specific strengths.")
                .improvementPlan("1. Practice explaining role-specific fundamentals clearly.\n"
                        + "2. Use the STAR method for behavioral questions.\n"
                        + "3. Practice role-relevant design, process, or tradeoff questions with real examples.")
                .interviewerVerdict("Insufficient data for a complete verdict. Keep interviewing!")
                .categoryInsights(List.of())
                .sessionCount(count)
                .avgScore(avgScore)
                .bestScore(bestScore)
                .trend(trend)
                .build();
    }
}
