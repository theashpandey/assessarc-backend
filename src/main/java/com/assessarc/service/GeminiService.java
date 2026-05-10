package com.assessarc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.assessarc.config.AppProperties;
import com.assessarc.model.GeminiUsageLog;
import com.assessarc.repository.GeminiUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final AppProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GeminiUsageRepository geminiUsageRepository;

    private static final String DEFAULT_ROLE = "software_engineer";
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(USAGE_ZONE);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM").withZone(USAGE_ZONE);

    private static final List<String> COMMON_CATEGORIES = List.of("problem_solving", "behavioral");
    private static final Set<String> FRESHER_LEVELS = Set.of("fresher", "1_3");

    private static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry("software_engineer", "Software Engineer"),
            Map.entry("java_developer", "Java Developer"),
            Map.entry("python_developer", "Python Developer"),
            Map.entry("react_developer", "React Developer"),
            Map.entry("full_stack_developer", "Full Stack Developer"),
            Map.entry("backend_engineer", "Backend Engineer"),
            Map.entry("frontend_engineer", "Frontend Engineer"),
            Map.entry("data_scientist", "Data Scientist"),
            Map.entry("data_engineer", "Data Engineer"),
            Map.entry("devops_engineer", "DevOps Engineer"),
            Map.entry("cloud_engineer", "Cloud Engineer"),
            Map.entry("qa_automation_engineer", "QA Automation Engineer"),
            Map.entry("mobile_developer", "Mobile Developer"),
            Map.entry("software_architect", "Software Architect"),
            Map.entry("engineering_manager", "Engineering Manager"),
            Map.entry("product_manager", "Product Manager"),
            Map.entry("hr_recruiter", "HR / Recruiter")
    );

    private static final Map<String, String> EXPERIENCE_LABELS = Map.ofEntries(
            Map.entry("fresher", "Fresher"),
            Map.entry("1_3", "1-3 years"),
            Map.entry("3_5", "3-5 years"),
            Map.entry("5_7", "5-7 years"),
            Map.entry("7_10", "7-10 years"),
            Map.entry("10_12", "10-12 years"),
            Map.entry("12_15", "12-15 years"),
            Map.entry("15_20", "15-20 years"),
            Map.entry("20_25", "20-25 years"),
            Map.entry("25_30", "25-30 years"),
            Map.entry("30_35", "30-35 years"),
            Map.entry("35_plus", "35+ years")
    );

    private static final Map<String, List<String>> ROLE_CATEGORIES = Map.ofEntries(
            Map.entry("software_engineer", List.of("programming", "api_design", "databases", "system_design", "testing", "debugging")),
            Map.entry("java_developer", List.of("java_core", "oops", "multithreading", "spring", "microservices", "system_design")),
            Map.entry("python_developer", List.of("python_core", "oops", "django_fastapi", "api_design", "databases", "testing")),
            Map.entry("react_developer", List.of("javascript", "react", "frontend_architecture", "testing", "api_design", "performance")),
            Map.entry("full_stack_developer", List.of("javascript", "react", "api_design", "databases", "system_design", "cloud_devops")),
            Map.entry("backend_engineer", List.of("api_design", "databases", "microservices", "system_design", "testing", "cloud_devops")),
            Map.entry("frontend_engineer", List.of("javascript", "react", "frontend_architecture", "testing", "performance", "accessibility")),
            Map.entry("data_scientist", List.of("python_core", "statistics", "machine_learning", "sql", "data_modeling", "experimentation")),
            Map.entry("data_engineer", List.of("python_core", "sql", "data_modeling", "distributed_systems", "cloud_devops", "data_quality")),
            Map.entry("devops_engineer", List.of("linux", "ci_cd", "cloud_devops", "kubernetes", "observability", "security")),
            Map.entry("cloud_engineer", List.of("cloud_devops", "system_design", "networking", "security", "kubernetes", "cost_optimization")),
            Map.entry("qa_automation_engineer", List.of("testing", "automation_frameworks", "api_testing", "ci_cd", "debugging", "quality_strategy")),
            Map.entry("mobile_developer", List.of("mobile_architecture", "ui_state", "api_design", "testing", "performance", "release_management")),
            Map.entry("software_architect", List.of("architecture", "system_design", "microservices", "cloud_devops", "security", "leadership")),
            Map.entry("engineering_manager", List.of("people_management", "leadership", "delivery", "hiring", "stakeholder_management", "technical_judgment")),
            Map.entry("product_manager", List.of("product_strategy", "prioritization", "stakeholder_management", "metrics", "execution", "user_research")),
            Map.entry("hr_recruiter", List.of("hiring", "sourcing", "employee_relations", "communication", "process_management", "stakeholder_management"))
    );

    public record ResumeInsights(String summary, List<String> categories) {}

    // ── Role / Experience Helpers ──

    public String normalizeRole(String role) {
        if (role == null || role.isBlank()) return DEFAULT_ROLE;
        String normalized = role.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return ROLE_CATEGORIES.containsKey(normalized) ? normalized : DEFAULT_ROLE;
    }

    public boolean isSupportedRole(String role) {
        if (role == null || role.isBlank()) return false;
        String normalized = role.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return ROLE_CATEGORIES.containsKey(normalized);
    }

    public String normalizeExperience(String experience) {
        if (experience == null || experience.isBlank()) return "1_3";
        String normalized = experience.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return EXPERIENCE_LABELS.containsKey(normalized) ? normalized : "1_3";
    }

    public boolean isSupportedExperience(String experience) {
        if (experience == null || experience.isBlank()) return false;
        String normalized = experience.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return EXPERIENCE_LABELS.containsKey(normalized);
    }

    public String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(normalizeRole(role), "Software Engineer");
    }

    public String experienceLabel(String experience) {
        return EXPERIENCE_LABELS.getOrDefault(normalizeExperience(experience), "1-3 years");
    }

    public boolean isFresher(String experienceLevel) {
        return FRESHER_LEVELS.contains(normalizeExperience(experienceLevel));
    }

    public boolean roleRequiresCoding(String role) {
        String normalized = normalizeRole(role);
        return Set.of("java_developer", "python_developer", "react_developer", "full_stack_developer",
                     "backend_engineer", "frontend_engineer", "software_engineer").contains(normalized);
    }

    public List<String> categoriesForRole(String role) {
        LinkedHashSet<String> categories = new LinkedHashSet<>(COMMON_CATEGORIES);
        categories.addAll(ROLE_CATEGORIES.getOrDefault(normalizeRole(role), ROLE_CATEGORIES.get(DEFAULT_ROLE)));
        return new ArrayList<>(categories);
    }

    public List<String> categoriesForInterview(String role, List<String> resumeCategories) {
        LinkedHashSet<String> categories = new LinkedHashSet<>(COMMON_CATEGORIES);
        if (resumeCategories != null) {
            for (String category : resumeCategories) {
                String normalized = normalizeCategory(category);
                if (!normalized.isBlank()) categories.add(normalized);
            }
        }
        categories.addAll(ROLE_CATEGORIES.getOrDefault(normalizeRole(role), ROLE_CATEGORIES.get(DEFAULT_ROLE)));
        return categories.stream().limit(8).toList();
    }

    public String categoryLabel(String category) {
        if (category == null || category.isBlank()) return "";
        String[] parts = category.split("_");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) continue;
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return String.join(" ", words);
    }

    // ── Custom Exceptions ──

    public static class GeminiQuotaException extends RuntimeException {
        public GeminiQuotaException(String message) { super(message); }
    }

    public static class GeminiUnavailableException extends RuntimeException {
        public GeminiUnavailableException(String message) { super(message); }
    }

    // ── Core Gemini API Call ──

    public String callGemini(String userPrompt, String systemPrompt) {
        return callGeminiWithTemp(userPrompt, systemPrompt, 0.7);
    }

    public String callGemini(String userPrompt, String systemPrompt,
                             String userId, String interviewId, String callType) {
        return callGeminiWithTemp(userPrompt, systemPrompt, 0.7, userId, interviewId, callType);
    }

    public String callGeminiWithTemp(String userPrompt, String systemPrompt, double temperature) {
        return callGeminiWithTemp(userPrompt, systemPrompt, temperature, null, null, "unknown");
    }

    public String callGeminiWithTemp(String userPrompt, String systemPrompt, double temperature,
                                     String userId, String interviewId, String callType) {
        boolean usageRecorded = false;
        try {
            String apiKey = props.getGemini().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new GeminiUnavailableException("AI service is not configured");
            }

            var contents = new ArrayList<Map<String, Object>>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                contents.add(Map.of("role", "user",
                        "parts", List.of(Map.of("text", systemPrompt))));
                contents.add(Map.of("role", "model",
                        "parts", List.of(Map.of("text", "Understood. I will follow these instructions."))));
            }
            contents.add(Map.of("role", "user",
                    "parts", List.of(Map.of("text", userPrompt))));

            var body = Map.of(
                    "contents", contents,
                    "generationConfig", Map.of(
                            "maxOutputTokens", maxOutputTokensFor(callType),
                            "temperature", temperature,
                            "topP", 0.95,
                            "topK", 40
                    )
            );

            String url = props.getGemini().getUrl() + "?key=" + apiKey;

            String responseStr = webClientBuilder.build()
                    .post().uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            var json = objectMapper.readTree(responseStr);

            if (json.has("error")) {
                String errMsg = json.get("error").get("message").asText();
                log.error("Gemini API error response: {}", sanitizeSecret(errMsg));
                recordUsage(userId, interviewId, callType, "ERROR", json.path("usageMetadata"), errMsg);
                usageRecorded = true;
                if (isQuotaMessage(errMsg)) {
                    throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
                }
                throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
            }

            var candidates = json.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("Gemini returned no candidates. Full response: {}", sanitizeSecret(responseStr));
                throw new GeminiUnavailableException("AI service returned no response. Please try again.");
            }

            var parts = candidates.get(0).path("content").path("parts");
            var sb = new StringBuilder();
            for (var part : parts) {
                if (part.has("text")) sb.append(part.get("text").asText());
            }
            String result = sb.toString().trim();
            recordUsage(userId, interviewId, callType, "SUCCESS", json.path("usageMetadata"), null);
            usageRecorded = true;
            log.debug("Gemini response ({}chars): {}",
                    result.length(), result.substring(0, Math.min(100, result.length())));
            return result;

        } catch (WebClientResponseException e) {
            recordUsage(userId, interviewId, callType, "ERROR", null,
                    "HTTP " + e.getStatusCode().value() + ": " +
                    truncate(sanitizeSecret(e.getResponseBodyAsString()), 180));
            if (isQuotaStatus(e.getStatusCode().value()) || isQuotaMessage(e.getResponseBodyAsString())) {
                log.warn("Gemini quota/rate limit reached: status={}", e.getStatusCode().value());
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
            }
            log.warn("Gemini request failed: status={}", e.getStatusCode().value());
            throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
        } catch (RuntimeException e) {
            if (!usageRecorded) {
                recordUsage(userId, interviewId, callType, "ERROR", null, sanitizeSecret(e.getMessage()));
            }
            if (isQuotaMessage(e.getMessage())) {
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
            }
            throw e;
        } catch (Exception e) {
            log.error("Gemini API call failed: {}", sanitizeSecret(e.getMessage()));
            recordUsage(userId, interviewId, callType, "ERROR", null, sanitizeSecret(e.getMessage()));
            throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
        }
    }

    // ── Resume Parsing ──

    public String parseResume(String resumeText) {
        return parseResumeInsights(resumeText).summary();
    }

    public ResumeInsights parseResumeInsights(String resumeText) {
        return parseResumeInsights(resumeText, null, null);
    }

    public ResumeInsights parseResumeInsights(String resumeText, String userId, String interviewId) {
      String excerpt = resumeText.substring(0, Math.min(5000, resumeText.length()));
      String prompt = """
              Resume text:

              %s

              Analyze this resume for a mock interview platform.
              Return ONLY valid JSON with:
              {
                "summary": "under 300 words, concise but specific",
                "categories": ["category_one", "category_two", "category_three"]
              }
              Summary rules:
              - extract and summarize candidate name, years of experience, skills and frameworks, projects, job titles, tech stack.
              - Be concise, under 300 words. Focus on skills.

              Category rules:
              - categories must be lower_snake_case
              - choose 4 to 6 interview categories
              - categories must reflect the candidate's actual more focused tech skills, cloud skills, tools, architecture depth, leadership, domain strengths etc in resume
              - always include problem_solving if the resume shows technical or analytical work
              - use behavioral for people-heavy or leadership-heavy profiles when relevant
              """.formatted(excerpt);

      try {
          Map<String, Object> raw = safeParseJsonObject(callGemini(
                  prompt,
                  "You are a resume parser for realistic human-like mock interviews. Extract only evidence-backed summary and interview categories. Return only JSON.",
                  userId,
                  interviewId,
                  "resume_parse"
          ));
          String summary = String.valueOf(raw.getOrDefault("summary", "")).trim();
          List<String> categories = extractCategories(raw.get("categories"));
          if (summary.isBlank()) {
              summary = fallbackResumeSummary(resumeText);
          }
          if (categories.isEmpty()) {
              categories = fallbackResumeCategories(resumeText);
          }
          return new ResumeInsights(summary, categories);
      } catch (GeminiQuotaException | GeminiUnavailableException e) {
          log.warn("Using resume insights fallback because Gemini is unavailable: {}", e.getMessage());
          return new ResumeInsights(fallbackResumeSummary(resumeText), fallbackResumeCategories(resumeText));
      }
  }
    // ── Question Generation ──

    public List<Map<String, Object>> generateQuestions(String resumeSummary,
                                                        List<String> existingTexts,
                                                        int count,
                                                        String role,
                                                        String experienceLevel,
                                                        List<String> allowedCategories) {
        return generateQuestions(resumeSummary, existingTexts, count, role, experienceLevel,
                allowedCategories, null, null, "question_generation");
    }

    public List<Map<String, Object>> generateQuestions(String resumeSummary,
                                                        List<String> existingTexts,
                                                        int count,
                                                        String role,
                                                        String experienceLevel,
                                                        List<String> allowedCategories,
                                                        String userId,
                                                        String interviewId,
                                                        String callType) {
        return generateQuestions(resumeSummary, existingTexts, count, role, experienceLevel,
                allowedCategories, 30, userId, interviewId, callType); // default 30 min
    }

    public List<Map<String, Object>> generateQuestions(String resumeSummary,
                                                        List<String> existingTexts,
                                                        int count,
                                                        String role,
                                                        String experienceLevel,
                                                        List<String> allowedCategories,
                                                        int durationMinutes,
                                                        String userId,
                                                        String interviewId,
                                                        String callType) {
        boolean fresher = isFresher(experienceLevel);
        String existing = existingTexts.isEmpty() ? "none" : String.join("\n- ", existingTexts);
        String allowed = String.join(", ", allowedCategories);
        String roleLabel = roleLabel(role);
        String expLabel = experienceLabel(experienceLevel);

        // Determine coding questions
        boolean hasCoding = roleRequiresCoding(role);
        int codingCount = 0;
        String codingInstructions = "";
        if (hasCoding) {
            if (durationMinutes == 30) {
                codingCount = 1;
                codingInstructions = "Include exactly 1 CODING question (difficulty: easy).";
            } else if (durationMinutes == 60) {
                codingCount = 2;
                codingInstructions = "Include exactly 2 CODING questions: first easy, second medium.";
            }
        }
        int textCount = count - codingCount;

        // ── Fresher-specific vs experienced prompt ──
        String depthInstructions;
        String questionStyleGuide;

        if (fresher) {
            depthInstructions = """
                    This candidate is a FRESHER or very early in their career.
                    They likely have limited or no real work experience — only college projects, coursework, or self-learning.
                    DO NOT ask about production systems, team leadership, scaling, architecture decisions, or things that require years of experience.
                    
                    Instead, use these question styles:
                    - "Walk me through a project you built, even a college one — what was the toughest part?"
                    - "In Java, how does X actually work under the hood? Can you explain with an example?"
                    - "Imagine you are building a simple REST API — how would you structure it?"
                    - "Have you tried using Y? What did you find interesting or confusing about it?"
                    - "If your SQL query was running slow, where would you start looking?"
                    - "What is your understanding of X? Can you give me a real example?"
                    
                    Mix: 40% fundamentals, 30% simple scenario/imagine-you-are-building, 20% project-based (college ok), 10% behavioral/curiosity.
                    Test UNDERSTANDING and CURIOSITY, not years of production experience.
                    Be encouraging in tone — this is meant to feel like a friendly campus interview.
                    """;
            questionStyleGuide = """
                    Question style rules for freshers:
                    - Ask "walk me through", "how does X work", "imagine you're building", "what happens when", "have you tried"
                    - Avoid "in your current role", "in production", "your team", "scaling to millions"
                    - Make scenarios simple: "small app", "your project", "if you had to build from scratch"
                    - Vary openers: don't start every question the same way
                    """;
        } else {
            depthInstructions = """
                    This is an EXPERIENCED candidate (%s experience).
                    Ask questions that test real depth, tradeoffs, ownership, and battle-tested judgment.
                    
                    Use these question styles:
                    - "Tell me about a time you had to make a tough call on X — what did you choose and why?"
                    - "Walk me through a production issue you debugged — what was your process?"
                    - "You're designing a system that needs to handle Y — how would you approach it?"
                    - "What's a tradeoff you made in your last project that you'd handle differently now?"
                    - "How do you decide between X and Y when building Z?"
                    - "Your team disagrees on an approach — how do you resolve it?"
                    
                    Mix: 30%% scenario/situation, 30%% design/tradeoff, 20%% project-grounded, 20%% behavioral/leadership.
                    Probe for OWNERSHIP, IMPACT, and REASONING — not just textbook knowledge.
                    """.formatted(expLabel);
            questionStyleGuide = """
                    Question style rules for experienced candidates:
                    - Use "tell me about a time", "walk me through", "how would you design", "what tradeoff did you make"
                    - Reference their resume projects naturally if relevant, but don't make every question about resume
                    - Test real judgment: "why did you choose X over Y?", "what would you do differently?"
                    - Vary openers broadly — no two questions should feel similar in structure
                    """;
        }

        String prompt = String.format(
                "Target interview role: %s\n" +
                "Experience level: %s\n\n" +
                "Candidate profile from resume:\n%s\n\n" +
                "Already asked questions (DO NOT repeat or ask similar ones):\n- %s\n\n" +
                "%s\n\n" +
                "%s\n\n" +
                "Generate exactly %d TEXT questions and %d CODING questions.\n" +
                "Allowed categories: %s\n\n" +
                "%s\n\n" +
                "Return ONLY a valid JSON array:\n" +
                "[{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"medium\",\"type\":\"text\"}, ...]\n\n" +
                "For CODING questions, include:\n" +
                "{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"easy\",\"type\":\"coding\",\"codingData\":{\"language\":\"java\",\"expectedOutput\":\"...\",\"testCases\":[{\"input\":\"...\",\"expectedOutput\":\"...\"}],\"description\":\"...\"}}\n\n" +
                "Rules:\n" +
                "- category must be one of: %s\n" +
                "- difficulty: easy | medium | hard (NEVER hard for coding)\n" +
                "- type: text | coding\n" +
                "- For coding: language based on role (java for java_developer, javascript for react_developer, etc.)\n" +
                "- DO NOT mention 'resume', 'your profile', 'as per your CV' in the question text\n" +
                "- Each question must sound like a real human interviewer saying it out loud\n" +
                "- No bullet-style or list-style questions (e.g. avoid 'List the types of...')\n" +
                "- No repetitive openers across questions",
                roleLabel, expLabel, resumeSummary, existing,
                depthInstructions, questionStyleGuide,
                textCount, codingCount, allowed, codingInstructions, allowed
        );

        String systemPrompt = buildInterviewerSystemPrompt(roleLabel, fresher);

        try {
            String raw = callGeminiWithTemp(prompt, systemPrompt, 0.9, userId, interviewId, callType);
            List<Map<String, Object>> questions = safeParseJsonArray(raw);
            if (questions.isEmpty()) {
                log.warn("Gemini returned empty question list, using fallback");
                return fallbackQuestions(count, role, allowedCategories, fresher);
            }
            return questions;
        } catch (GeminiQuotaException | GeminiUnavailableException e) {
            log.warn("Gemini unavailable while generating questions: {}", e.getMessage());
            return fallbackQuestions(count, role, allowedCategories, fresher);
        }
    }

    // ── Feedback Generation ──

    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer,
                                    String role, String experienceLevel) {
        return generateFeedback(question, category, answer, prevQuestion, prevAnswer,
                role, experienceLevel, null, null);
    }

    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer,
                                    String role, String experienceLevel,
                                    String userId, String interviewId) {
        boolean fresher = isFresher(experienceLevel);
        String roleLabel = roleLabel(role);

        String prevCtx = "";
        if (prevQuestion != null && !prevQuestion.isBlank()
                && prevAnswer != null && !prevAnswer.isBlank()) {
            prevCtx = "Previous question: \"" + prevQuestion + "\"\n" +
                      "They answered: \"" + prevAnswer.substring(0, Math.min(120, prevAnswer.length())) + "...\"\n\n";
        }

        String answerSnippet = (answer != null && !answer.isBlank()) ? answer : "(no answer given)";

        // Fresher gets warmer, encouraging feedback; experienced gets more direct/critical
        String feedbackToneGuide = fresher
                ? """
                  This is a fresher candidate. Be warm, encouraging, and patient.
                  Even if the answer is incomplete, acknowledge what they got right first.
                  Give ONE clear thing to improve, but frame it gently — like a mentor, not a critic.
                  Example tone: "That's actually a good start! You got the concept right. One thing to add is..."
                  """
                : """
                  This is an experienced candidate. Be professional and direct.
                  Acknowledge what was good, but be specific about gaps or missed depth.
                  Treat them as a peer — skip over-praise, give honest calibration.
                  Example tone: "Good answer on X. What I'd push you on is the tradeoff around Y — in production, that matters because..."
                  """;

        String prompt = prevCtx +
                "Role being interviewed for: " + roleLabel + "\n" +
                "Experience level: " + experienceLabel(experienceLevel) + "\n" +
                "Question category: " + categoryLabel(category) + "\n" +
                "Question asked: \"" + question + "\"\n\n" +
                "Candidate's answer: \"" + answerSnippet + "\"\n\n" +
                feedbackToneGuide + "\n" +
                "Give 2-3 sentence verbal feedback exactly as you would say it in a live interview room.\n" +
                "Start with a natural opener — not 'Great job!' every time. Vary it.\n" +
                "Mention ONE thing they did well (be specific to their answer, not generic).\n" +
                "Mention ONE concrete thing to improve or add — something they can actually act on.\n" +
                "No bullet points. No markdown. Conversational spoken style only.";

        return callGemini(prompt,
                buildInterviewerSystemPrompt(roleLabel, fresher) +
                " Give short, natural, spoken feedback. Be specific to what they actually said. Sound human.",
                userId, interviewId, "answer_feedback");
    }

    // ── Score Calculation ──

    public Map<String, Object> calculateScores(List<Map<String, String>> qaList,
                                               String role, String experienceLevel,
                                               List<String> allowedCategories) {
        return calculateScores(qaList, role, experienceLevel, allowedCategories, null, null);
    }

    public Map<String, Object> calculateScores(List<Map<String, String>> qaList,
                                               String role, String experienceLevel,
                                               List<String> allowedCategories,
                                               String userId, String interviewId) {
        boolean fresher = isFresher(experienceLevel);
        String roleLabel = roleLabel(role);

        var sb = new StringBuilder("Interview Q&As to evaluate:\n\n");
        sb.append("Target role: ").append(roleLabel).append("\n");
        sb.append("Experience level: ").append(experienceLabel(experienceLevel)).append("\n");
        sb.append("Allowed scoring categories: ").append(String.join(", ", allowedCategories)).append("\n\n");

        for (var qa : qaList) {
            String catLabel = categoryLabel(qa.get("category"));
            String answer = qa.getOrDefault("answer", "(no answer)");
            if (answer.isBlank()) answer = "(no answer given)";
            sb.append("[").append(catLabel).append("] Q: ").append(qa.get("question"))
              .append("\nA: ").append(answer).append("\n\n");
        }

        // Fresher scoring: don't penalize for lacking production experience
        String scoringContext = fresher
                ? "This is a FRESHER candidate. Score based on conceptual clarity, curiosity, learning ability, " +
                  "and fundamentals — NOT on production experience or architectural depth. " +
                  "A strong fresher answer demonstrates understanding of concepts, logical thinking, and honest self-awareness."
                : "This is an experienced candidate. Score on depth of reasoning, tradeoffs mentioned, " +
                  "ownership shown, real-world judgment, and technical accuracy.";

        sb.append(scoringContext).append("\n\n")
          .append("Score each dimension out of 100. Be realistic — do not inflate scores.\n")
          .append("The categories object must only include these keys: ")
          .append(String.join(", ", allowedCategories)).append(".\n")
          .append("technical = role-specific professional depth (not just coding ability).\n")
          .append("Return ONLY valid JSON (no markdown):\n")
          .append("{\"technical\":75,\"communication\":80,\"problemSolving\":70,")
          .append("\"roleDepth\":78,\"overall\":76,")
          .append("\"categories\":{\"problem_solving\":72,\"behavioral\":85}}");

        try {
            String raw = callGeminiWithTemp(sb.toString(),
                    "You are a strict " + roleLabel + " interview evaluator. " +
                    "Score realistically based on actual answer quality. Do not give inflated scores. " +
                    "Return only valid JSON.",
                    0.3, userId, interviewId, "score_calculation");
            return parseJsonObjectOrThrow(raw);
        } catch (GeminiQuotaException | GeminiUnavailableException e) {
            log.warn("Gemini unavailable while scoring: {}", e.getMessage());
            throw e;
        }
    }

    // ── Interviewer System Prompt Builder ──

    /**
     * Builds a rich, consistent interviewer persona.
     * Fresher mode = warmer campus-style interviewer.
     * Experienced mode = senior technical interviewer.
     */
    private String buildInterviewerSystemPrompt(String roleLabel, boolean fresher) {
        if (fresher) {
            return "You are Sarah, a friendly and experienced " + roleLabel + " interviewer at a top tech company google. " +
                   "Right now you are conducting a campus or entry-level interview. " +
                   "Your style is warm, patient, and encouraging — you want to understand how the candidate THINKS, not just what they know. " +
                   "You ask questions that test curiosity, fundamentals, and potential. " +
                   "You avoid jargon that requires years of production experience. " +
                   "You sound like a human colleague, not a textbook. Never sound robotic or list-like. " +
                   "You genuinely care about helping freshers show their best.";
        } else {
            return "You are Sarah, a sharp and experienced " + roleLabel + " interviewer at a top tech company google. " +
                   "You conduct senior-level interviews. " +
                   "Your style is direct, professional, and intellectually curious — you probe for depth, tradeoffs, and real judgment. " +
                   "You ask questions the way a senior engineer or engineering manager would in a real interview room. " +
                   "You reference scenarios, real-world constraints, and 'what would you do if' situations naturally. " +
                   "You are never generic. You never ask textbook-style list questions. " +
                   "You sound like a smart human peer who has seen a lot of systems and candidates.";
        }
    }

    // ── JSON Parsing Helpers ──

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> safeParseJsonArray(String raw) {
        try {
            String text = cleanJson(raw);
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(text);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isObject()) continue;
                Map<String, String> m = new LinkedHashMap<>();
                Map<String, Object> converted = objectMapper.convertValue(item, Map.class);
                result.add(converted);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse JSON array: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> safeParseJsonObject(String raw) {
        try {
            String text = cleanJson(raw);
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse JSON object: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseJsonObjectOrThrow(String raw) {
        try {
            String text = cleanJson(raw);
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            throw new GeminiUnavailableException("AI service returned an invalid response. Please try again.");
        }
    }

    private String cleanJson(String raw) {
        return raw.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", "")
                  .replaceAll("```json|```", "")
                  .trim();
    }

    // ── Utilities ──

    private boolean isQuotaStatus(int status) {
        return status == 429;
    }

    private int maxOutputTokensFor(String callType) {
        String normalized = callType == null ? "" : callType;
        if (normalized.contains("question_generation")) return 4096;
        if (normalized.contains("feedback")) return 512;
        if (normalized.contains("score")) return 1024;
        if (normalized.contains("analysis")) return 2048;
        if (normalized.contains("resume_parse")) return 1024;
        return 2048;
    }

    private void recordUsage(String userId, String interviewId, String callType,
                             String status, JsonNode usageMetadata, String errorMessage) {
        long now = System.currentTimeMillis();
        JsonNode usage = usageMetadata != null ? usageMetadata : objectMapper.createObjectNode();
        int inputTokens = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);
        Instant instant = Instant.ofEpochMilli(now);

        geminiUsageRepository.save(GeminiUsageLog.builder()
                .userId(userId)
                .interviewId(interviewId)
                .callType(callType == null || callType.isBlank() ? "unknown" : callType)
                .status(status)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .createdAt(now)
                .day(DAY_FMT.format(instant))
                .month(MONTH_FMT.format(instant))
                .errorMessage(errorMessage == null ? null : truncate(errorMessage, 300))
                .build());
    }

    private boolean isQuotaMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("quota") || lower.contains("rate limit")
                || lower.contains("resource_exhausted")
                || lower.contains("too many requests")
                || lower.contains("429");
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public String sanitizeSecret(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)([?&]key=)[^\\s&]+", "$1[REDACTED]")
                .replaceAll("AIza[0-9A-Za-z_-]{20,}", "[REDACTED_API_KEY]");
    }

    // ── Fallback Helpers ──

    private List<Map<String, Object>> fallbackQuestions(int count, String role,
                                                         List<String> allowedCategories,
                                                         boolean fresher) {
        List<String> categories = (allowedCategories == null || allowedCategories.isEmpty())
                ? categoriesForRole(role) : allowedCategories;
        List<Map<String, Object>> fallback = new ArrayList<>();

        if (fresher) {
            fallback.add(Map.of(
                "question", "Walk me through a project you've built — what was your role and what was the toughest part you had to figure out?",
                "category", "behavioral", "difficulty", "easy"));
            fallback.add(Map.of(
                "question", "If you had to build a simple REST API for a to-do app, how would you approach it — what would you think about first?",
                "category", "problem_solving", "difficulty", "easy"));
        } else {
            fallback.add(Map.of(
                "question", "Tell me about a time you had to make a tough technical decision under pressure — what was the situation and what did you choose?",
                "category", "behavioral", "difficulty", "medium"));
            fallback.add(Map.of(
                "question", "Walk me through a production issue you had to debug. What was your process?",
                "category", "problem_solving", "difficulty", "medium"));
        }

        for (String category : categories) {
            if ("behavioral".equals(category) || "problem_solving".equals(category)) continue;
            String question = fresher
                    ? "How does " + categoryLabel(category) + " work in your understanding? Can you walk me through it with a simple example?"
                    : "What is a meaningful tradeoff you have navigated in " + categoryLabel(category) + " — and what would you do differently today?";
            fallback.add(Map.of("question", question, "category", category, "difficulty", fresher ? "easy" : "medium"));
        }

        return fallback.stream().limit(Math.max(1, Math.min(count, fallback.size()))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCategories(Object rawCategories) {
        LinkedHashSet<String> categories = new LinkedHashSet<>(COMMON_CATEGORIES);
        if (rawCategories instanceof List<?> rawList) {
            for (Object item : rawList) {
                String normalized = normalizeCategory(String.valueOf(item));
                if (!normalized.isBlank()) categories.add(normalized);
            }
        }
        return categories.stream().limit(8).toList();
    }

    private List<String> fallbackResumeCategories(String resumeText) {
        return categoriesForRole(inferRoleFromResume(resumeText));
    }

    private String fallbackResumeSummary(String resumeText) {
        return resumeText.substring(0, Math.min(1200, resumeText.length()));
    }

    private String inferRoleFromResume(String resumeText) {
        String lower = resumeText == null ? "" : resumeText.toLowerCase(Locale.ROOT);
        if (lower.contains("spring") || lower.contains("java ")) return "java_developer";
        if (lower.contains("react") || lower.contains("frontend")) return "frontend_engineer";
        if (lower.contains("python") || lower.contains("fastapi") || lower.contains("django")) return "python_developer";
        if (lower.contains("kubernetes") || lower.contains("devops") || lower.contains("terraform")) return "devops_engineer";
        if (lower.contains("machine learning") || lower.contains("data science")) return "data_scientist";
        if (lower.contains("product manager") || lower.contains("roadmap")) return "product_manager";
        if (lower.contains("recruiter") || lower.contains("talent acquisition") || lower.contains("hiring")) return "hr_recruiter";
        return DEFAULT_ROLE;
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}
