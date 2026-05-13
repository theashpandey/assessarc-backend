package com.assessarc.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.assessarc.dto.Dto;
import com.assessarc.model.Interview;
import com.assessarc.repository.InterviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enhanced Single Interview Analysis Service
 * 
 * Provides detailed, human-like feedback on individual interview sessions.
 * Key improvements:
 * 1. Rich context building with actual question-answer pairs
 * 2. Explicit system prompt with tone requirements and constraints
 * 3. Better structure detection (did they skip questions? ramble? hesitate?)
 * 4. Smart verdict alignment with scoring
 * 5. Specific, actionable improvement plans tied to actual answers
 * 6. No generic placeholders in output
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleInterviewAnalysisService {

    private final InterviewRepository interviewRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public Dto.PerformanceAnalysisResponse getDetailAnalysis(String uid, String interviewId) {
        // 1. FETCH & AUTHORIZE
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found"));
        if (!interview.getUserId().equals(uid)) {
            throw new RuntimeException("Unauthorized");
        }

        // 2. CHECK CACHE
        if (interview.getAnalysis() != null) {
            return toAnalysisResponse(interview.getAnalysis(),
                    interview.getScores() != null ? interview.getScores().getOverall() : 0);
        }

        // 3. EXTRACT QUESTIONS & ANSWERS
        List<Interview.QuestionAnswer> qas = interview.getQuestions() != null
                ? interview.getQuestions() : List.of();
        
        if (qas.isEmpty()) {
            return buildSingleInterviewFallback(interview, "No questions were found for this interview.");
        }

        // 4. BUILD RICH CONTEXT
        String richContext = buildRichInterviewContext(interview, qas);

        // 5. BUILD SYSTEM & USER PROMPTS WITH ENHANCED CONSTRAINTS
        String systemPrompt = buildEnhancedSystemPrompt(interview);
        String userPrompt = buildEnhancedUserPrompt(richContext, interview, qas);

        // 6. CALL GEMINI & PARSE
        try {
            String raw = geminiService.callGeminiWithTemp(
                    userPrompt,
                    systemPrompt,
                    0.3,  // Low temp for consistency
                    uid,
                    interviewId,
                    "single_interview_analysis"
            );

            // Parse and validate
            Dto.SingleInterviewAnalysis analysis = parseDetailedAnalysis(raw, interview, qas);

            // 7. VALIDATE ALIGNMENT (score vs verdict)
            analysis = validateAndFixAlignment(analysis, interview.getScores());

            // 8. SAVE TO DB
            Interview.Analysis dbAnalysis = Interview.Analysis.builder()
                    .overallAnalysis(analysis.getOverallAnalysis())
                    .communicationAnalysis(analysis.getCommunicationAnalysis())
                    .answeringFlowAnalysis(analysis.getAnsweringFlowAnalysis())
                    .strengthsSummary(analysis.getStrengthsSummary())
                    .improvementPlan(analysis.getImprovementPlan())
                    .interviewerVerdict(analysis.getInterviewerVerdict())
                    .generatedAt(System.currentTimeMillis())
                    .build();
            
            interviewRepository.updateAnalysis(interviewId, dbAnalysis);

            return toAnalysisResponse(dbAnalysis, 
                    interview.getScores() != null ? interview.getScores().getOverall() : 0);

        } catch (Exception e) {
            log.error("Single interview analysis failed: {}", e.getMessage(), e);
            return buildSingleInterviewFallback(interview, 
                    "AI analysis is temporarily unavailable. Please try again.");
        }
    }

    /**
     * Build rich context with actual question-answer pairs and observations
     * This is what differentiates generic feedback from personalized feedback
     */
    private String buildRichInterviewContext(Interview interview, List<Interview.QuestionAnswer> qas) {
        StringBuilder sb = new StringBuilder();

        // HEADER
        sb.append("SINGLE INTERVIEW SESSION ANALYSIS\n");
        sb.append("==================================\n\n");

        // BASIC INFO
        sb.append("Interview Details:\n");
        sb.append("- Role: ").append(geminiService.roleLabel(interview.getInterviewRole())).append("\n");
        sb.append("- Experience Level: ").append(geminiService.experienceLabel(interview.getExperienceLevel())).append("\n");
        sb.append("- Duration: ").append(interview.getDurationMinutes()).append(" minutes\n");

        // SCORES (with context)
        if (interview.getScores() != null) {
            Interview.Scores scores = interview.getScores();
            sb.append("- Overall Score: ").append(scores.getOverall()).append("%\n");
            sb.append("- Technical: ").append(scores.getTechnical()).append("%\n");
            sb.append("- Communication: ").append(scores.getCommunication()).append("%\n");
            sb.append("- Problem Solving: ").append(scores.getProblemSolving()).append("%\n");
        }
        sb.append("\n");

        // STRUCTURE ANALYSIS
        int skipped = (int) qas.stream()
                .filter(q -> q.getAnswer() == null || q.getAnswer().isBlank())
                .count();
        sb.append("Session Structure:\n");
        sb.append("- Total Questions: ").append(qas.size()).append("\n");
        sb.append("- Answered: ").append(qas.size() - skipped).append("\n");
        sb.append("- Skipped/No Answer: ").append(skipped).append("\n\n");

        // ACTUAL QUESTIONS & ANSWERS WITH OBSERVATIONS
        sb.append("DETAILED Q&A ANALYSIS:\n");
        sb.append("======================\n\n");

        for (int i = 0; i < qas.size(); i++) {
            Interview.QuestionAnswer q = qas.get(i);
            sb.append("=== QUESTION ").append(i + 1).append(" ===\n");
            sb.append("Category: ").append(q.getCategory()).append("\n");
            sb.append("Question: ").append(q.getQuestion()).append("\n");

            // ANSWER HANDLING (different for coding vs behavioral)
            if ("coding".equals(q.getType()) && q.getCodingSubmission() != null) {
                Interview.CodingSubmission code = q.getCodingSubmission();
                sb.append("Type: CODING\n");
                sb.append("Language: ").append(code.getLanguage()).append("\n");
                sb.append("Code Score: ").append(code.getScore()).append("/100\n");
                sb.append("Code Quality Notes: ").append(code.getAiEvaluation()).append("\n");
            } else {
                String answer = (q.getAnswer() == null || q.getAnswer().isBlank()) 
                    ? "(No answer provided)" 
                    : q.getAnswer();
                sb.append("Type: ").append(q.getType() != null ? q.getType() : "BEHAVIORAL").append("\n");
                sb.append("Answer: ").append(answer).append("\n");
            }

            // LIVE FEEDBACK FROM INTERVIEWER (if available)
            if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
                sb.append("Interviewer Notes: ").append(q.getFeedback()).append("\n");
            }

            sb.append("\n");
        }

        // PATTERN DETECTION
        sb.append("OBSERVED PATTERNS:\n");
        sb.append("==================\n");
        
        // Detect hesitations
        int hesitantAnswers = (int) qas.stream()
                .filter(q -> q.getAnswer() != null && 
                        (q.getAnswer().toLowerCase().contains("i'm not sure") ||
                         q.getAnswer().toLowerCase().contains("i don't know") ||
                         q.getAnswer().toLowerCase().contains("um ") ||
                         q.getAnswer().toLowerCase().contains("uh ")))
                .count();
        
        if (hesitantAnswers > 0) {
            sb.append("- Hesitation markers detected in ").append(hesitantAnswers).append(" answer(s)\n");
        }

        // Detect rambling
        int rambling = (int) qas.stream()
                .filter(q -> q.getAnswer() != null && q.getAnswer().length() > 500)
                .count();
        
        if (rambling > 0) {
            sb.append("- Long/rambling responses in ").append(rambling).append(" answer(s) (may indicate lack of focus)\n");
        }

        // Detect incomplete answers
        if (skipped > 0) {
            sb.append("- ").append(skipped).append(" question(s) left unanswered (concerning for completeness)\n");
        }

        sb.append("\n");

        return sb.toString();
    }

    /**
     * System prompt that enforces human-like, specific, actionable feedback
     */
    private String buildEnhancedSystemPrompt(Interview interview) {
        String roleLabel = geminiService.roleLabel(interview.getInterviewRole());
        String experienceLabel = geminiService.experienceLabel(interview.getExperienceLevel());

        return "You are Sarah, a senior technical interviewer with 12+ years of hiring experience. " +
               "You're reviewing a candidate's performance in this single mock interview session. " +
               "Your feedback MUST be specific, fair, honest, and actionable.\n\n" +

               "TONE REQUIREMENTS:\n" +
               "- Honest about gaps, but never demoralizing\n" +
               "- Grounded in SPECIFIC EVIDENCE from their actual answers (not generic)\n" +
               "- Empathetic: if they skipped questions, note it neutrally (not judgmentally)\n" +
               "- Professional but conversational (like mentoring, not corporate speak)\n" +
               "- Personalized—every piece of feedback ties to THIS interview, not templates\n\n" +

               "CRITICAL CONSTRAINTS:\n" +
               "1. Your verdict MUST align with their overall score:\n" +
               "   - 75%+ = STRONG HIRE (performs at expected level)\n" +
               "   - 50-75% = HIRE WITH COACHING (fundamentals present, needs work)\n" +
               "   - 30-50% = NOT YET (significant gaps; coachable but not ready)\n" +
               "   - <30% = RECONSIDER (major concerns; unlikely fit)\n" +
               "2. Every strength claim MUST cite a specific answer from this interview\n" +
               "3. Every weakness MUST come with concrete next steps (not vague advice)\n" +
               "4. All improvement suggestions MUST be time-bound (2 weeks, 4 weeks, NOT 'practice more')\n" +
               "5. No inventing strengths that weren't shown; no exaggerating weaknesses\n" +
               "6. Distinguish between: knowledge gaps → they don't know / communication gaps → they know but can't explain / confidence gaps → they hesitate\n\n" +

               "DO NOT:\n" +
               "- Generate generic advice like 'improve communication' without specifics\n" +
               "- Contradict yourself (praise their technical depth then call them junior)\n" +
               "- Use corporate jargon or filler language\n" +
               "- Be harsh about nervousness or a bad first interview\n" +
               "- Make claims not supported by their answer evidence\n" +
               "- Include bullet points in text; use flowing sentences\n\n" +

               "EXPECTED ROLE FOR COMPARISON:\n" +
               "- Role: " + roleLabel + "\n" +
               "- Expected Experience Level: " + experienceLabel + "\n\n" +

               "OUTPUT REQUIREMENTS:\n" +
               "- Return ONLY valid JSON, no markdown backticks, no preamble\n" +
               "- Every field must be real prose (3-4 sentences for analysis fields)\n" +
               "- No placeholder text, no empty arrays with []\n";
    }

    /**
     * User prompt with clear task and explicit JSON format
     */
    private String buildEnhancedUserPrompt(String richContext, Interview interview, 
                                          List<Interview.QuestionAnswer> qas) {
        int totalQuestions = qas.size();
        int answeredCount = (int) qas.stream()
                .filter(q -> q.getAnswer() != null && !q.getAnswer().isBlank())
                .count();

        return richContext +
               "\n\n" +
               "=== ANALYSIS TASK ===\n" +
               "Analyze this candidate's performance in this single interview session. " +
               "They answered " + answeredCount + " out of " + totalQuestions + " questions.\n\n" +
               "Focus on:\n" +
               "1. What this session reveals about their actual level vs. claimed experience\n" +
               "2. Communication clarity and structure of their explanations\n" +
               "3. How they handled difficult/skipped questions\n" +
               "4. Specific strengths observed and concrete weaknesses\n" +
               "5. What they need to work on before the next interview\n\n" +

               "Return ONLY this JSON structure (no markdown, no explanation):\n" +
               "{\n" +
               "  \"overallAnalysis\": \"3-4 sentences. Who are they based on this session? What's their actual level? " +
               "What stands out? Tie directly to their answers, not generic.\",\n" +
               "  \"communicationAnalysis\": \"2-3 sentences. How do they communicate? Clear or rambling? " +
               "Confident or hesitant? Back up with at least one specific example from their answers.\",\n" +
               "  \"answeringFlowAnalysis\": \"2-3 sentences. Did they structure answers well? " +
               "Were they complete or incomplete? Direct or evasive? Reference specific questions.\",\n" +
               "  \"strengthsSummary\": \"What are they genuinely good at based on THIS interview? " +
               "Mention specific categories or answers where they shined. No generic praise.\",\n" +
               "  \"improvementPlan\": \"3-4 specific, time-bound areas. Format: " +
               "'[Area]: [specific action] in [timeframe]. Why: [reference to their answer]. Example: " +
               "[concrete exercise]. Do NOT use bullet points or brackets. Write as flowing text.\",\n" +
               "  \"interviewerVerdict\": \"One sentence: [VERDICT]: [reason tied to their score and answers]. " +
               "Options: STRONG HIRE / HIRE WITH COACHING / NOT YET / RECONSIDER.\",\n" +
               "  \"nextInterviewFocus\": \"If they interview again, have them focus on [ONE specific area]. " +
               "Goal: demonstrate [what they need to show]. Second person voice.\"\n" +
               "}\n";
    }

    /**
     * Parse the raw Gemini response into structured analysis
     */
    private Dto.SingleInterviewAnalysis parseDetailedAnalysis(String raw, Interview interview,
                                                              List<Interview.QuestionAnswer> qas) {
        try {
            // Clean JSON
            String clean = raw.replaceAll("```json|```", "").trim();
            int start = clean.indexOf('{');
            int end = clean.lastIndexOf('}');
            if (start >= 0 && end > start) {
                clean = clean.substring(start, end + 1);
            }

            JsonNode root = objectMapper.readTree(clean);

            return Dto.SingleInterviewAnalysis.builder()
                    .overallAnalysis(getTextOrDefault(root, "overallAnalysis", "Analysis unavailable."))
                    .communicationAnalysis(getTextOrDefault(root, "communicationAnalysis", "No communication data."))
                    .answeringFlowAnalysis(getTextOrDefault(root, "answeringFlowAnalysis", "No flow analysis."))
                    .strengthsSummary(getTextOrDefault(root, "strengthsSummary", "No strengths identified."))
                    .improvementPlan(getTextOrDefault(root, "improvementPlan", "Continue practicing."))
                    .interviewerVerdict(getTextOrDefault(root, "interviewerVerdict", "Needs evaluation."))
                    .nextInterviewFocus(getTextOrDefault(root, "nextInterviewFocus", "Focus on fundamentals."))
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse analysis response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    /**
     * Validate and fix score-verdict alignment
     */
    private Dto.SingleInterviewAnalysis validateAndFixAlignment(Dto.SingleInterviewAnalysis analysis,
                                                               Interview.Scores scores) {
        if (scores == null) return analysis;

        int score = scores.getOverall();
        String verdict = analysis.getInterviewerVerdict();

        String expectedVerdict = getExpectedVerdict(score);

        // Check if verdict aligns with score
        if (verdict == null || !verdict.toUpperCase().contains(expectedVerdict)) {
            log.warn("Verdict-score mismatch. Score: {}, Verdict: {}. Correcting...", score, verdict);
            
            // Extract reason from verdict if available
            String reason = extractReasonFromVerdict(verdict, score);
            analysis.setInterviewerVerdict(expectedVerdict + ": " + reason);
        }

        return analysis;
    }

    /**
     * Determine expected verdict based on score
     */
    private String getExpectedVerdict(int score) {
        if (score >= 75) return "STRONG HIRE";
        if (score >= 50) return "HIRE WITH COACHING";
        if (score >= 30) return "NOT YET";
        return "RECONSIDER";
    }

    /**
     * Extract reason from verdict or generate default
     */
    private String extractReasonFromVerdict(String verdict, int score) {
        if (verdict != null && verdict.length() > 10) {
            // Try to extract reason after colon
            int colonIndex = verdict.indexOf(':');
            if (colonIndex != -1 && colonIndex < verdict.length() - 1) {
                return verdict.substring(colonIndex + 1).trim();
            }
        }

        // Generate default reason based on score
        if (score >= 75) {
            return "Performance aligns with role requirements. Ready for next steps.";
        } else if (score >= 50) {
            return "Fundamentals are present but gaps exist. Focused coaching can bridge the gap.";
        } else if (score >= 30) {
            return "Significant gaps between claimed and demonstrated experience. Continue learning.";
        } else {
            return "Major concerns about fit for this role level. Reconsider after substantial upskilling.";
        }
    }

    /**
     * Safe getter for JSON fields (prevents [] placeholder issue)
     */
    private String getTextOrDefault(JsonNode node, String fieldName, String defaultValue) {
        JsonNode field = node.path(fieldName);
        
        if (field.isEmpty() || field.isNull()) {
            return defaultValue;
        }

        String value = field.asText("").trim();
        
        // Filter out placeholder patterns
        if (value.isEmpty() || value.equals("[]") || value.equals("[") || 
            value.startsWith("[") && value.endsWith("]") && value.length() < 5) {
            return defaultValue;
        }

        return value;
    }

    /**
     * Convert stored analysis to response DTO
     */
    private Dto.PerformanceAnalysisResponse toAnalysisResponse(Interview.Analysis analysis, int score) {
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis(analysis.getOverallAnalysis())
                .communicationAnalysis(analysis.getCommunicationAnalysis())
                .answeringFlowAnalysis(analysis.getAnsweringFlowAnalysis())
                .strengthsSummary(analysis.getStrengthsSummary())
                .improvementPlan(analysis.getImprovementPlan())
                .interviewerVerdict(analysis.getInterviewerVerdict())
                .sessionCount(1)
                .avgScore(score)
                .bestScore(score)
                .generatedAt(analysis.getGeneratedAt())
                .build();
    }

    /**
     * Fallback response when analysis fails
     */
    private Dto.PerformanceAnalysisResponse buildSingleInterviewFallback(Interview interview, String message) {
        int score = interview.getScores() != null ? interview.getScores().getOverall() : 0;
        
        String verdict = score >= 75 ? "STRONG HIRE" : 
                        score >= 50 ? "HIRE WITH COACHING" : 
                        score >= 30 ? "NOT YET" : "RECONSIDER";

        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis(message)
                .communicationAnalysis("Unable to generate detailed feedback at this time.")
                .answeringFlowAnalysis("Please try again later.")
                .strengthsSummary("Manual review recommended.")
                .improvementPlan("Contact support for detailed feedback.")
                .interviewerVerdict(verdict + ": Unable to generate analysis.")
                .sessionCount(1)
                .avgScore(score)
                .bestScore(score)
                .generatedAt(System.currentTimeMillis())
                .build();
    }
}