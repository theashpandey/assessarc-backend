package com.assessarc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.assessarc.dto.Dto;
import com.assessarc.model.Interview;
import com.assessarc.repository.InterviewRepository;
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
        List<Interview> reportable = interviewRepository.findReportableByUserId(uid, 10);
        List<Interview> last7 = reportable.stream()
                .filter(i -> "COMPLETED".equals(i.getStatus()) && i.getScores() != null)
                .limit(7)
                .collect(Collectors.toList());

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

        Map<String, Integer> categoryAverages = categoryAverages(last7);
        String strongestCategory = categoryAverages.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        String weakestCategory = categoryAverages.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");

        // Trend: compare first half vs second half
        String trend = "neutral";
        if (last7.size() >= 3) {
            int split = Math.max(1, last7.size() / 2);
            double recentAvg = last7.subList(0, split).stream()
                    .filter(i -> i.getScores() != null)
                    .mapToInt(i -> i.getScores().getOverall()).average().orElse(0);
            double olderAvg = last7.subList(split, last7.size()).stream()
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
        sb.append("Computed category averages: ").append(categoryAverages).append("\n");
        sb.append("Strongest computed category: ").append(strongestCategory).append("\n");
        sb.append("Weakest computed category: ").append(weakestCategory).append("\n\n");

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
                    sb.append("A: ").append(answer.substring(0, Math.min(450, answer.length())));
                    if (answer.length() > 450) sb.append("...");
                    sb.append("\n");
                    if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
                        sb.append("Interviewer feedback: ").append(q.getFeedback()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        String systemPrompt = "You are a senior interviewer and career coach with 15 years across engineering, data, product, management, architecture, and HR hiring loops. " +
                "You have just reviewed this candidate's complete interview history. " +
                "Give a specific, actionable analysis that feels personal and useful enough that the candidate wants to practice again. " +
                "Be honest without being discouraging. Reference specific patterns from their actual answers. " +
                "Do not invent skills, projects, or scores that are not present in the data. " +
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
                  "skillProfileSummary": "One concise paragraph summarizing the candidate's current skill profile using the practiced role, experience level, strongest categories, weakest categories, and answer evidence.",
                  "nextInterviewGoal": "One specific goal for the next mock interview, written in second person and tied to their weakest high-impact skill.",
                  "practiceDrills": ["short drill 1 tied to weak category", "short drill 2 tied to communication or structure", "short drill 3 tied to role fundamentals"],
                  "categoryInsights": [
                    {"category": "java_core", "avgScore": 75, "insight": "Shows X pattern in answers...", "advice": "Should focus on Y specifically..."},
                    {"category": "oops", "avgScore": 80, "insight": "...", "advice": "..."}
                  ]
                }
                """;

        try {
            String raw = geminiService.callGeminiWithTemp(userPrompt, systemPrompt, 0.4,
                    uid, null, "performance_analysis");
            return parseAnalysisResponse(raw, last7.size(), avgScore, bestScore, trend, categoryAverages);
        } catch (Exception e) {
            log.error("Analysis generation failed: {}", e.getMessage());
            return buildFallbackAnalysis(last7, avgScore, bestScore, trend, categoryAverages);
        }
    }

    private Dto.PerformanceAnalysisResponse parseAnalysisResponse(
            String raw, int sessionCount, int avgScore, int bestScore, String trend,
            Map<String, Integer> computedCategoryAverages) {
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
                    String category = cat.path("category").asText();
                    if (!computedCategoryAverages.containsKey(category)) continue;
                    insights.add(Dto.CategoryInsight.builder()
                            .category(category)
                            .avgScore(computedCategoryAverages.get(category))
                            .insight(cat.path("insight").asText())
                            .advice(cat.path("advice").asText())
                            .build());
                }
            }
            if (insights.isEmpty()) {
                insights = fallbackCategoryInsights(computedCategoryAverages);
            }

            return Dto.PerformanceAnalysisResponse.builder()
                    .overallAnalysis(root.path("overallAnalysis").asText())
                    .communicationAnalysis(root.path("communicationAnalysis").asText())
                    .answeringFlowAnalysis(root.path("answeringFlowAnalysis").asText())
                    .strengthsSummary(root.path("strengthsSummary").asText())
                    .improvementPlan(root.path("improvementPlan").asText())
                    .interviewerVerdict(root.path("interviewerVerdict").asText())
                    .skillProfileSummary(root.path("skillProfileSummary").asText())
                    .nextInterviewGoal(root.path("nextInterviewGoal").asText())
                    .practiceDrills(parseStringList(root.path("practiceDrills")))
                    .categoryInsights(insights)
                    .sessionCount(sessionCount)
                    .avgScore(avgScore)
                    .bestScore(bestScore)
                    .trend(trend)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse analysis response: {}", e.getMessage());
            return buildFallbackAnalysis(List.of(), avgScore, bestScore, trend, computedCategoryAverages);
        }
    }

    private Dto.PerformanceAnalysisResponse buildFallbackAnalysis(
            List<Interview> sessions, int avgScore, int bestScore, String trend,
            Map<String, Integer> categoryAverages) {
        int count = sessions.size();
        String weakCategory = categoryAverages.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(entry -> geminiService.categoryLabel(entry.getKey()))
                .orElse("your weakest role-specific area");
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
                .skillProfileSummary("Your current profile will become clearer as you complete more scored interviews.")
                .nextInterviewGoal("In your next interview, focus on making one answer in " + weakCategory + " more structured and example-driven.")
                .practiceDrills(List.of(
                        "Record a 90-second answer for one weak-category question and remove filler.",
                        "Use Point, Example, Tradeoff for one technical answer.",
                        "Write three follow-up questions a real interviewer might ask about your last project."
                ))
                .categoryInsights(fallbackCategoryInsights(categoryAverages))
                .sessionCount(count)
                .avgScore(avgScore)
                .bestScore(bestScore)
                .trend(trend)
                .build();
    }

    private Map<String, Integer> categoryAverages(List<Interview> sessions) {
        Map<String, List<Integer>> scoresByCategory = new LinkedHashMap<>();
        for (Interview session : sessions) {
            if (session.getScores() == null || session.getScores().getCategories() == null) continue;
            session.getScores().getCategories().forEach((category, score) -> {
                if (category == null || category.isBlank() || score == null || score <= 0) return;
                scoresByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(score);
            });
        }
        Map<String, Integer> averages = new LinkedHashMap<>();
        scoresByCategory.forEach((category, values) -> averages.put(category,
                (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0))));
        return averages;
    }

    private List<Dto.CategoryInsight> fallbackCategoryInsights(Map<String, Integer> categoryAverages) {
        return categoryAverages.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(5)
                .map(entry -> Dto.CategoryInsight.builder()
                        .category(entry.getKey())
                        .avgScore(entry.getValue())
                        .insight("Your recent answers in " + geminiService.categoryLabel(entry.getKey())
                                + " average " + entry.getValue() + "%.")
                        .advice("Practice one concise answer with a direct point, concrete example, and tradeoff.")
                        .build())
                .collect(Collectors.toList());
    }

    private List<String> parseStringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }
}
