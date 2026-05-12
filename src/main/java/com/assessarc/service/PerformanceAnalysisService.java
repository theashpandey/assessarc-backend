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

/**
 * Enhanced Performance Analysis Service using Gemini API
 * 
 * Key improvements over original:
 * 1. Better system prompt that enforces human-like tone
 * 2. Detailed context about candidate's actual answers
 * 3. Constraint validation to prevent contradictions
 * 4. Score-verdict alignment checks before returning
 * 5. Empathy and specificity requirements
 * 6. No generic drills - all tied to actual answers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAnalysisService {

    private final InterviewRepository interviewRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public Dto.PerformanceAnalysisResponse generateAnalysis(String uid) {
        List<Interview> reportable = interviewRepository.findReportableByUserId(uid, 10);
        List<Interview> last7 = reportable.stream()
                .filter(i -> "COMPLETED".equals(i.getStatus()) && i.getScores() != null)
                .limit(7)
                .collect(Collectors.toList());

        if (last7.isEmpty()) {
            return buildEmptyStateResponse();
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

        // Trend analysis
        String trend = calculateTrend(last7);
        boolean isFirstInterview = last7.size() == 1;
        
        // Detect contextual factors
        boolean isHighNervous = detectNervousness(last7);
        boolean showsFatigue = detectFatigue(last7);

        // Build rich context for Gemini with explicit instructions for human-like feedback
        String context = buildDetailedContext(last7, avgScore, bestScore, categoryAverages, 
                                              strongestCategory, weakestCategory, trend, 
                                              isFirstInterview, isHighNervous, showsFatigue);

        // Call Gemini with improved prompt
        String systemPrompt = buildHumanLikeSystemPrompt(avgScore, isFirstInterview);
        String userPrompt = buildDetailedUserPrompt(context, avgScore, last7.size());

        try {
            String raw = geminiService.callGeminiWithTemp(userPrompt, systemPrompt, 0.3,
                    uid, null, "performance_analysis");
            
            // Parse and validate the response
            Dto.PerformanceAnalysisResponse response = parseAnalysisResponse(
                    raw, last7.size(), avgScore, bestScore, trend, categoryAverages);
            
            // Validate score-verdict alignment before returning
            response = validateAndFixAlignment(response, avgScore);
            
            return response;
        } catch (Exception e) {
            log.error("Analysis generation failed: {}", e.getMessage(), e);
            return buildFallbackAnalysis(last7, avgScore, bestScore, trend, categoryAverages);
        }
    }

    /**
     * System prompt that enforces human-like, empathetic, honest feedback
     * This is the key to getting AI to generate like a real interviewer
     */
    private String buildHumanLikeSystemPrompt(int avgScore, boolean isFirstInterview) {
        return "You are an experienced senior interviewer with 15+ years of hiring across all levels. " +
               "You're reviewing a candidate's interview performance to give them feedback that is:" +
               "\n\n" +
               "TONE REQUIREMENTS:" +
               "\n- Honest but encouraging (never brutal, never generic)" +
               "\n- Specific with concrete examples from their actual answers" +
               "\n- Empathetic about their context (nervousness, first interview, fatigue)" +
               "\n- Personalized - not a template response" +
               "\n- Professional but conversational (like talking to a friend, not a robot)" +
               "\n\n" +
               "CRITICAL CONSTRAINTS:" +
               "\n- Your verdict MUST align with the score (no contradictions)" +
               "\n  • 75%+ = STRONG HIRE" +
               "\n  • 45-75% = HIRE WITH COACHING" +
               "\n  • 30-45% = NOT YET" +
               "\n  • <30% = REJECT" +
               "\n- Coaching must be specific and time-bound (weeks, not vague)" +
               "\n- All drills must tie to actual weaknesses from their answers" +
               "\n- Celebrate real strengths (don't invent non-existent ones)" +
               "\n- Distinguish between knowledge gaps vs communication gaps vs confidence issues" +
               "\n\n" +
               "DO NOT:" +
               "\n- Generate generic advice (every candidate is unique)" +
               "\n- Make claims not supported by their answer evidence" +
               "\n- Use corporate jargon (e.g., 'synergize', 'leverage')" +
               "\n- Be discouraging about a first interview" +
               "\n- Contradict yourself (score vs verdict, strengths vs weaknesses)" +
               "\n\n" +
               "OUTPUT:" +
               "\n- Return ONLY valid JSON, no markdown, no preamble" +
               "\n- Every field must be a real sentence, not placeholder text";
    }

    /**
     * Detailed user prompt with actual answer excerpts and explicit guidance
     */
    private String buildDetailedUserPrompt(String context, int avgScore, int sessionCount) {
        return context +
               "\n\n" +
               "=== YOUR FEEDBACK TASK ===\n" +
               "Generate feedback that would make this candidate feel heard and know exactly what to do next.\n" +
               "Reference their specific answers. Be honest about gaps. Celebrate actual strengths.\n\n" +
               "Return ONLY this JSON structure (no markdown, no explanation):\n" +
               "{\n" +
               "  \"overallAnalysis\": \"2-4 sentences. Who is this person? What's their level? What stands out? Be specific to THEIR answers.\",\n" +
               "  \"communicationAnalysis\": \"2-3 sentences. How do they communicate? Clear? Rambling? Hesitant? Give examples from actual answers.\",\n" +
               "  \"answeringFlowAnalysis\": \"2-3 sentences. Do they structure answers? Miss key points? Strong technically but weak explaining? Back up with examples.\",\n" +
               "  \"strengthsSummary\": \"What are they genuinely good at? Mention categories. Back with evidence from answers.\",\n" +
               "  \"improvementPlan\": \"3-4 specific, time-bound improvement areas. Each must be actionable (not 'practice more'). Examples: 'Spend 2 weeks on [concept]', 'Record yourself answering [type]', 'Do [specific thing]'.\",\n" +
               "  \"interviewerVerdict\": \"Your hiring decision. MUST align with score. Format: '[VERDICT]: [1-2 sentence reasoning]'. Options: STRONG HIRE / HIRE WITH COACHING / NOT YET / REJECT.\",\n" +
               "  \"skillProfileSummary\": \"One paragraph. Their current level. What experience level should they be at? Categories they excel in? Where they need work? Tone: honest, encouraging.\",\n" +
               "  \"nextInterviewGoal\": \"ONE specific, measurable goal for their next interview. Tied to their weakest area. Second person.\",\n" +
               "  \"practiceDrills\": [\"Drill 1 tied to communication or structure\", \"Drill 2 tied to weakest category\", \"Drill 3 tied to role fundamentals\"],\n" +
               "  \"categoryInsights\": [\n" +
               "    {\"category\": \"[category_name]\", \"avgScore\": [number], \"insight\": \"What you observed in their answers\", \"advice\": \"Specific next step\"}\n" +
               "  ]\n" +
               "}\n";
    }

    /**
     * Build detailed context with actual answer excerpts
     */
    private String buildDetailedContext(List<Interview> last7, int avgScore, int bestScore,
                                        Map<String, Integer> categoryAverages, String strongestCategory,
                                        String weakestCategory, String trend, boolean isFirstInterview,
                                        boolean isHighNervous, boolean showsFatigue) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("CANDIDATE INTERVIEW PERFORMANCE DATA\n");
        sb.append("=====================================\n\n");
        
        // Context about their situation
        if (isFirstInterview) {
            sb.append("CONTEXT: This is their FIRST interview - expectations should reflect that.\n\n");
        }
        if (isHighNervous) {
            sb.append("CONTEXT: Candidate showed signs of nervousness (hesitation markers in multiple answers).\n\n");
        }
        if (showsFatigue) {
            sb.append("CONTEXT: Candidate shows fatigue - performance declined across sessions.\n\n");
        }
        
        // Scores
        sb.append("OVERALL PERFORMANCE:\n");
        sb.append("- Average Score: ").append(avgScore).append("%\n");
        sb.append("- Best Score: ").append(bestScore).append("%\n");
        sb.append("- Sessions Analyzed: ").append(last7.size()).append("\n");
        sb.append("- Trend: ").append(trend).append("\n\n");
        
        // Categories
        sb.append("CATEGORY BREAKDOWN:\n");
        sb.append("- Strongest: ").append(strongestCategory).append(" (").append(categoryAverages.get(strongestCategory)).append("%)\n");
        sb.append("- Weakest: ").append(weakestCategory).append(" (").append(categoryAverages.get(weakestCategory)).append("%)\n\n");
        
        // All categories
        sb.append("All categories:\n");
        categoryAverages.forEach((cat, score) -> 
            sb.append("  • ").append(cat).append(": ").append(score).append("%\n")
        );
        sb.append("\n");
        
        // Actual answer excerpts
        sb.append("ACTUAL ANSWERS & PATTERNS:\n");
        sb.append("==========================\n\n");
        
        for (int s = 0; s < last7.size(); s++) {
            Interview iv = last7.get(s);
            sb.append("=== SESSION ").append(s + 1).append(" ===\n");
            sb.append("Role: ").append(iv.getInterviewRole()).append("\n");
            sb.append("Experience Level: ").append(iv.getExperienceLevel()).append("\n");
            sb.append("Duration: ").append(iv.getDurationMinutes()).append(" min\n");
            
            if (iv.getScores() != null) {
                sb.append("Scores - Overall: ").append(iv.getScores().getOverall()).append("%, ");
                sb.append("Technical: ").append(iv.getScores().getTechnical()).append("%, ");
                sb.append("Communication: ").append(iv.getScores().getCommunication()).append("%, ");
                sb.append("Problem Solving: ").append(iv.getScores().getProblemSolving()).append("%\n");
            }
            
            // Show actual questions and answers
            if (iv.getQuestions() != null && !iv.getQuestions().isEmpty()) {
                sb.append("\nAnswers from this session:\n");
                for (Interview.QuestionAnswer q : iv.getQuestions()) {
                    String answer = q.getAnswer() != null ? q.getAnswer().trim() : "";
                    
                    if (answer.equals("(skipped)") || answer.isBlank()) {
                        sb.append("\nQ [").append(q.getCategory()).append("]: ").append(q.getQuestion()).append("\n");
                        sb.append("A: (SKIPPED)\n");
                        continue;
                    }
                    
                    sb.append("\nQ [").append(q.getCategory()).append("]: ").append(q.getQuestion()).append("\n");
                    sb.append("A: ").append(answer.substring(0, Math.min(30000, answer.length())));
                    if (answer.length() > 30000) sb.append("...");
                    sb.append("\n");
                    
                    // Add interviewer feedback if available
                    if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
                        sb.append("Interviewer note: ").append(q.getFeedback()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        
        return sb.toString();
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

    /**
     * Validate and fix score-verdict alignment
     * If AI generated a verdict that doesn't match the score, adjust it
     */
    private Dto.PerformanceAnalysisResponse validateAndFixAlignment(
            Dto.PerformanceAnalysisResponse response, int avgScore) {
        
        String verdict = response.getInterviewerVerdict();
        String expectedVerdict = getExpectedVerdict(avgScore);
        
        // If verdict doesn't align with score, fix it but keep the reasoning
        if (verdict != null && !verdict.toUpperCase().contains(expectedVerdict)) {
            log.warn("Verdict-score mismatch detected. Score: {}, Verdict: {}. Correcting to: {}", 
                     avgScore, verdict, expectedVerdict);
            
            String reasoning = buildVerdictReasoning(avgScore, response.getStrengthsSummary());
            response.setInterviewerVerdict(expectedVerdict + ": " + reasoning);
        }
        
        return response;
    }

    private String getExpectedVerdict(int avgScore) {
        if (avgScore >= 75) return "STRONG HIRE";
        if (avgScore >= 45) return "HIRE WITH COACHING";
        if (avgScore >= 30) return "NOT YET";
        return "REJECT";
    }

    private String buildVerdictReasoning(int avgScore, String strengthsSummary) {
        if (avgScore >= 75) {
            return "You're performing at a strong level. Your technical knowledge and communication are solid. You're ready to contribute from day one.";
        } else if (avgScore >= 45) {
            return "You have the fundamentals. With 4-6 weeks of focused coaching on communication and depth, you'll be interview-ready at a higher level.";
        } else if (avgScore >= 30) {
            return "You're still developing. Re-interview after 8-12 weeks of focused study. This is coachable.";
        } else {
            return "Significant gaps exist. Consider this feedback and revisit in 6+ months.";
        }
    }

    private Map<String, Integer> categoryAverages(List<Interview> sessions) {
        Map<String, List<Integer>> scoresByCategory = new LinkedHashMap<>();
        for (Interview session : sessions) {
            if (session.getScores() == null || session.getScores().getCategories() == null) continue;
            session.getScores().getCategories().forEach((category, score) -> {
                if (category != null && !category.isBlank() && score != null && score > 0) {
                    scoresByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(score);
                }
            });
        }
        Map<String, Integer> averages = new LinkedHashMap<>();
        scoresByCategory.forEach((category, values) -> averages.put(category,
                (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0))));
        return averages;
    }

    private String calculateTrend(List<Interview> sessions) {
        if (sessions.size() < 3) return "neutral";
        
        int split = Math.max(1, sessions.size() / 2);
        double recentAvg = sessions.subList(0, split).stream()
                .mapToInt(i -> i.getScores().getOverall())
                .average().orElse(0);
        double olderAvg = sessions.subList(split, sessions.size()).stream()
                .mapToInt(i -> i.getScores().getOverall())
                .average().orElse(0);
        
        if (recentAvg > olderAvg + 3) return "improving";
        if (recentAvg < olderAvg - 3) return "declining";
        return "neutral";
    }

    private boolean detectNervousness(List<Interview> sessions) {
        long hesitantCount = sessions.stream()
                .flatMap(s -> s.getQuestions() != null ? s.getQuestions().stream() : java.util.stream.Stream.empty())
                .map(q -> q.getAnswer() != null ? q.getAnswer().toLowerCase() : "")
                .filter(answer -> answer.contains("um ") || answer.contains("like ") || 
                               answer.contains("so ") || answer.contains("uh ") ||
                               answer.contains("yeah so") || answer.contains("i guess"))
                .count();

        long totalAnswers = sessions.stream()
                .flatMap(s -> s.getQuestions() != null ? s.getQuestions().stream() : java.util.stream.Stream.empty())
                .count();

        return totalAnswers > 0 && (hesitantCount * 100 / totalAnswers) > 40;
    }

    private boolean detectFatigue(List<Interview> sessions) {
        if (sessions.size() < 3) return false;
        
        int lastScore = sessions.get(0).getScores().getOverall();
        int firstScore = sessions.get(sessions.size() - 1).getScores().getOverall();
        
        return lastScore < firstScore - 10;
    }

    private List<Dto.CategoryInsight> fallbackCategoryInsights(Map<String, Integer> categoryAverages) {
        return categoryAverages.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(5)
                .map(entry -> Dto.CategoryInsight.builder()
                        .category(entry.getKey())
                        .avgScore(entry.getValue())
                        .insight("Your recent answers in " + entry.getKey() + " averaged " + entry.getValue() + "%.")
                        .advice("Focus on this area with targeted practice.")
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

    private Dto.PerformanceAnalysisResponse buildEmptyStateResponse() {
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis("Complete at least one interview to see your personalized analysis.")
                .communicationAnalysis("No data yet.")
                .answeringFlowAnalysis("No data yet.")
                .strengthsSummary("No data yet.")
                .improvementPlan("Start your first interview to get a detailed improvement plan.")
                .interviewerVerdict("No interviews completed yet.")
                .skillProfileSummary("Your profile will develop as you complete scored interviews.")
                .nextInterviewGoal("Complete your first interview to unlock personalized coaching.")
                .practiceDrills(List.of(
                        "Schedule and complete your first mock interview.",
                        "Focus on clarity—take your time to think before answering.",
                        "Record yourself; listen to playback and note areas to improve."
                ))
                .categoryInsights(new ArrayList<>())
                .sessionCount(0)
                .avgScore(0)
                .bestScore(0)
                .trend("neutral")
                .build();
    }

    private Dto.PerformanceAnalysisResponse buildFallbackAnalysis(
            List<Interview> sessions, int avgScore, int bestScore, String trend,
            Map<String, Integer> categoryAverages) {
        
        int count = sessions.size();
        String verdict = getExpectedVerdict(avgScore);
        
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis("Based on " + count + " session(s), you averaged " + avgScore + "%. " +
                               "You have some good instincts, but there are areas to develop.")
                .communicationAnalysis("Your communication could be clearer. Practice structuring your answers " +
                                     "with specific examples before diving in.")
                .answeringFlowAnalysis("Work on going deeper with your explanations. It's not just about the what, " +
                                     "but also the why and how.")
                .strengthsSummary("You demonstrate awareness of important concepts. With practice, " +
                                "you can articulate them better.")
                .improvementPlan("1. Record yourself answering questions and listen for patterns.\n" +
                               "2. Use STAR method for behavioral questions (Situation, Task, Action, Result).\n" +
                               "3. Practice explaining technical concepts without assuming knowledge.")
                .interviewerVerdict(verdict + ": " + buildVerdictReasoning(avgScore, ""))
                .skillProfileSummary("You're at the beginning of your journey. Keep practicing and learning from each interview.")
                .nextInterviewGoal("In your next interview, focus on one thing: structure one answer clearly with " +
                                 "specific examples before moving to the next question.")
                .practiceDrills(List.of(
                        "Record a 90-second answer to one question and remove filler words.",
                        "Practice the STAR method with at least 3 behavioral questions.",
                        "Explain one technical concept to someone who doesn't know the domain."
                ))
                .categoryInsights(new ArrayList<>())
                .sessionCount(count)
                .avgScore(avgScore)
                .bestScore(bestScore)
                .trend(trend)
                .build();
    }
}