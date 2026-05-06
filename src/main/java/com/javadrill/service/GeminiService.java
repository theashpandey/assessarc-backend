package com.javadrill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadrill.config.AppProperties;
import com.javadrill.model.GeminiUsageLog;
import com.javadrill.repository.GeminiUsageRepository;
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

 
    public Map<String, Boolean> apis = new LinkedHashMap<>(Map.of(
            "AIzaSyA5ZSxoQwpOY1L0s9tPK7gRxJBRZbLdqv0", true,
            "AIzaSyCRs3WXzdC_b-PKpQBAcY2nagvZ9y-1tkU", true,
            "AIzaSyDomS7-4cwoKy3B8DX5BsofQ-w0Kybsp7A", true,
            "AIzaSyAkAY89mhhck93wQVV27bfV_-XT8PPyGU4", true
    ));
    public String getActiveKey(Map<String, Boolean> apiKeys) {
      for (Map.Entry<String, Boolean> entry : apiKeys.entrySet()) {
          if (entry.getValue()) {
              return entry.getKey();
          }
      }
      throw new RuntimeException("No active API keys available");
  }
    public void markKeyInactive(Map<String, Boolean> apiKeys, String key) {
      apiKeys.put(key, false);
  }
    private static final List<String> COMMON_CATEGORIES = List.of("problem_solving", "behavioral");
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
                if (!normalized.isBlank()) {
                    categories.add(normalized);
                }
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

    public static class GeminiQuotaException extends RuntimeException {
        public GeminiQuotaException(String message) {
            super(message);
        }
    }

    public static class GeminiUnavailableException extends RuntimeException {
        public GeminiUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Core Gemini API call — handles content structure correctly
     */
    public String callGemini(String userPrompt, String systemPrompt) {
        return callGeminiWithTemp(userPrompt, systemPrompt, 0.7);
    }

    public String callGemini(String userPrompt, String systemPrompt, String userId, String interviewId, String callType) {
        return callGeminiWithTemp(userPrompt, systemPrompt, 0.7, userId, interviewId, callType);
    }

    public String callGeminiWithTemp(String userPrompt, String systemPrompt, double temperature) {
        return callGeminiWithTemp(userPrompt, systemPrompt, temperature, null, null, "unknown");
    }

    public String callGeminiWithTemp(String userPrompt, String systemPrompt, double temperature,
                                     String userId, String interviewId, String callType) {
       
     // String apiKey = null;
      boolean usageRecorded = false;
      try {
          String apiKey = props.getGemini().getApiKey();
          // apiKey = getActiveKey(apis);
            if (apiKey == null || apiKey.isBlank()) {
                throw new GeminiUnavailableException("AI service is not configured");
            }

            var contents = new ArrayList<Map<String, Object>>();

            // System prompt as first user turn (Gemini doesn't have a dedicated system role)
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
                            "maxOutputTokens", 20480,
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

            // Check for API errors
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
            log.debug("Gemini response ({}chars): {}", result.length(),
                    result.substring(0, Math.min(100, result.length())));
            return result;

        } catch (WebClientResponseException e) {
            recordUsage(userId, interviewId, callType, "ERROR", null,
                    "HTTP " + e.getStatusCode().value() + ": " + truncate(sanitizeSecret(e.getResponseBodyAsString()), 180));
            if (isQuotaStatus(e.getStatusCode().value()) || isQuotaMessage(e.getResponseBodyAsString())) {
                log.warn("Gemini quota/rate limit reached: status={} body={}",
                        e.getStatusCode().value(), truncate(sanitizeSecret(e.getResponseBodyAsString()), 180));
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
            }
            log.warn("Gemini request failed: status={} body={}",
                    e.getStatusCode().value(), truncate(sanitizeSecret(e.getResponseBodyAsString()), 180));
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
           // markKeyInactive(apis, apiKey);
            recordUsage(userId, interviewId, callType, "ERROR", null, sanitizeSecret(e.getMessage()));
            throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
        }
    }

    /**
     * Parse resume — returns concise summary for question generation
     */
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
                  "summary": "under 250 words, concise but specific",
                  "categories": ["category_one", "category_two", "category_three"]
                }
                Summary rules:
                - extract and summarize candidate name, years of experience, skills and frameworks, projects, job titles, tech stack.
                - Be concise, under 250 words. Focus on skills.

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

    /**
     * Generate interview questions tailored to resume.
     * BUG FIX: questions must never repeat those in existingTexts.
     */
    public List<Map<String, String>> generateQuestions(String resumeSummary,
                                                        List<String> existingTexts,
                                                        int count,
                                                        String role,
                                                        String experienceLevel,
                                                        List<String> allowedCategories) {
        return generateQuestions(resumeSummary, existingTexts, count, role, experienceLevel, allowedCategories,
                null, null, "question_generation");
    }

    public List<Map<String, String>> generateQuestions(String resumeSummary,
                                                        List<String> existingTexts,
                                                        int count,
                                                        String role,
                                                        String experienceLevel,
                                                        List<String> allowedCategories,
                                                        String userId,
                                                        String interviewId,
                                                        String callType) {
        String existing = existingTexts.isEmpty() ? "none" :
                String.join("\n- ", existingTexts);
        String allowed = String.join(", ", allowedCategories);
        String roleLabel = roleLabel(role);
        String expLabel = experienceLabel(experienceLevel);

        String prompt = String.format(
                "Target interview role: %s\n" +
                "Experience level: %s\n" +
                "Candidate profile from resume:\n%s\n\n" +
                "Already selected questions (DO NOT ask similar ones):\n- %s\n\n" +
                "Generate exactly %d NEW, unique interview questions for this role and experience level. " +
                "Make them conversational, natural, NOT robotic. " +
                "Questions must be resume-based: use the candidate's real projects, tools, responsibilities, achievements, and tech stack where possible. " +
                "Vary categories across: %s\n" +
                "Problem solving and behavioral are common for every role; other categories must match the chosen role and resume. " +
                "Each question should sound like a real senior interviewer asking it in a live interview. Avoid textbook phrasing, list-style wording, and generic prompts.\n" +
                "Tune depth to %s: fresher questions should test fundamentals; senior questions should test tradeoffs, ownership, architecture, leadership, and impact.\n\n" +
                "Return ONLY valid JSON array:\n" +
                "[{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"medium\"}, ...]\n" +
                "category must be one of: %s\n" +
                "difficulty: easy | medium | hard",
                roleLabel, expLabel, resumeSummary, existing, count,
                allowed, expLabel, allowed
        );

        try {
            String raw = callGeminiWithTemp(prompt,
                    "You are Sarah, a senior "+roleLabel+" interviewer at a top product company google. " +
                    "Generate natural, varied, role-specific resume based interview questions that sound human and grounded in the candidate's actual background. Return only valid JSON array.",
                    0.9, userId, interviewId, callType);
            return safeParseJsonArray(raw);
        } catch (GeminiQuotaException | GeminiUnavailableException e) {
            log.warn("Gemini unavailable while generating questions; using fallback questions: {}", e.getMessage());
            return fallbackQuestions(count, role, allowedCategories);
        }
    }


    /**
     * Generate conversational feedback for a single answer.
     * Sounds like a human interviewer, not a bot.
     */
    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer,
                                    String role, String experienceLevel) {
        return generateFeedback(question, category, answer, prevQuestion, prevAnswer, role, experienceLevel,
                null, null);
    }

    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer,
                                    String role, String experienceLevel,
                                    String userId, String interviewId) {
        String prevCtx = "";
        if (prevQuestion != null && !prevQuestion.isBlank() && prevAnswer != null && !prevAnswer.isBlank()) {
            prevCtx = "Previous question: \"" + prevQuestion + "\"\n" +
                      "Candidate answered: \"" + prevAnswer.substring(0, Math.min(120, prevAnswer.length())) + "...\"\n\n";
        }

        String answerSnippet = answer != null ? answer : "(no answer given)";

        String prompt = prevCtx +
                "Target role: " + roleLabel(role) + "\n" +
                "Experience level: " + experienceLabel(experienceLevel) + "\n" +
                "Current question [" + categoryLabel(category) + "]: \"" + question + "\"\n\n" +
                "Candidate's answer: \"" + answerSnippet + "\"\n\n" +
                "Give 2-3 sentence verbal feedback exactly as a human interviewer would say it live. " +
                "Start naturally — like 'Good answer!', 'That's a solid start,', 'Interesting approach,' etc. " +
                "Mention ONE thing they did well. Mention ONE specific thing to improve or add. " +
                "Sound warm but professional. No bullet points. No markdown. Conversational spoken style.";

        return callGemini(prompt,
                "You are Sarah, a friendly but professional senior "+role+" interviewer at google. " +
                "Give short, natural spoken feedback — the kind you'd hear in a real interview room. " +
                "Be specific, not generic. Sound human.",
                userId, interviewId, "answer_feedback");
    }

    /**
     * Score completed interview — returns scores map
     */
    public Map<String, Object> calculateScores(List<Map<String, String>> qaList,
                                               String role,
                                               String experienceLevel,
                                               List<String> allowedCategories) {
        return calculateScores(qaList, role, experienceLevel, allowedCategories, null, null);
    }

    public Map<String, Object> calculateScores(List<Map<String, String>> qaList,
                                               String role,
                                               String experienceLevel,
                                               List<String> allowedCategories,
                                               String userId,
                                               String interviewId) {
        var sb = new StringBuilder("Interview Q&As to evaluate:\n\n");
        sb.append("Target role: ").append(roleLabel(role)).append("\n");
        sb.append("Experience level: ").append(experienceLabel(experienceLevel)).append("\n");
        sb.append("Allowed scoring categories: ").append(String.join(", ", allowedCategories)).append("\n\n");
        for (var qa : qaList) {
            String catLabel = categoryLabel(qa.get("category"));
            String answer = qa.getOrDefault("answer", "(no answer)");
            if (answer.isBlank()) answer = "(no answer given)";
            sb.append("[").append(catLabel).append("] Q: ").append(qa.get("question"))
              .append("\nA: ").append(answer).append("\n\n");
        }
        sb.append("Score each dimension out of 100 based on the actual answers. Be realistic and strict.\n")
          .append("The categories object must include only these role-relevant keys: ")
          .append(String.join(", ", allowedCategories)).append(".\n")
          .append("technical means role-specific professional depth, not only coding.\n")
          .append("Return ONLY valid JSON (no markdown):\n")
          .append("{\"technical\":75,\"communication\":80,\"problemSolving\":70,")
          .append("\"javaDepth\":78,\"overall\":76,")
          .append("\"categories\":{\"problem_solving\":72,\"behavioral\":85}}");

        try {
            String raw = callGeminiWithTemp(sb.toString(),
                    "You are a strict "+role+" interview evaluator at google. Score realistically based on answer quality. " +
                    "Do not give inflated scores. Return only valid JSON.",
                    0.3, userId, interviewId, "score_calculation");
            return safeParseJsonObject(raw);
        } catch (GeminiQuotaException | GeminiUnavailableException e) {
            log.warn("Gemini unavailable while scoring; using fallback scores: {}", e.getMessage());
            return fallbackScores(allowedCategories);
        }
    }

    // ── JSON Parsing Helpers ──

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> safeParseJsonArray(String raw) {
        try {
            String text = cleanJson(raw);
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(text);
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isObject()) continue;
                Map<String, String> m = new LinkedHashMap<>();
                item.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                result.add(m);
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
            return Map.of("overall", 70, "technical", 70, "communication", 70,
                          "problemSolving", 70, "javaDepth", 70, "categories", Map.of());
        }
    }

    private String cleanJson(String raw) {
        return raw.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", "")
                  .replaceAll("```json|```", "")
                  .trim();
    }

    private boolean isQuotaStatus(int status) {
        return status == 429;
    }

    private void recordUsage(String userId, String interviewId, String callType,
                             String status, JsonNode usageMetadata, String errorMessage) {
        long now = System.currentTimeMillis();
        JsonNode usage = usageMetadata != null ? usageMetadata : objectMapper.createObjectNode();
        int inputTokens = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);
        int totalTokens = usage.path("totalTokenCount").asInt(inputTokens + outputTokens);
        Instant instant = Instant.ofEpochMilli(now);

        geminiUsageRepository.save(GeminiUsageLog.builder()
                .userId(userId)
                .interviewId(interviewId)
                .callType(callType == null || callType.isBlank() ? "unknown" : callType)
                .status(status)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
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
                || lower.contains("resource_exhausted") || lower.contains("too many requests")
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

    private List<Map<String, String>> fallbackQuestions(int count, String role, List<String> allowedCategories) {
        List<String> categories = allowedCategories == null || allowedCategories.isEmpty()
                ? categoriesForRole(role) : allowedCategories;
        List<Map<String, String>> fallback = new ArrayList<>();
        fallback.add(Map.of("question", "Can you walk me through one recent project or responsibility from your resume and explain your exact contribution?", "category", "behavioral", "difficulty", "medium"));
        fallback.add(Map.of("question", "How do you break down an unfamiliar problem before deciding on an implementation or process?", "category", "problem_solving", "difficulty", "medium"));
        for (String category : categories) {
            if ("behavioral".equals(category) || "problem_solving".equals(category)) continue;
            fallback.add(Map.of(
                    "question", "For a " + roleLabel(role) + " interview, how would you demonstrate strong practical depth in " + categoryLabel(category) + " using an example from your resume?",
                    "category", category,
                    "difficulty", "medium"
            ));
        }
        return fallback.stream().limit(Math.max(1, Math.min(count, fallback.size()))).toList();
    }

    private Map<String, Object> fallbackScores(List<String> allowedCategories) {
        Map<String, Integer> categories = new LinkedHashMap<>();
        List<String> cats = allowedCategories == null || allowedCategories.isEmpty()
                ? List.of("problem_solving", "behavioral") : allowedCategories;
        for (String category : cats) {
            categories.put(category, 65);
        }
        return Map.of(
                "overall", 65,
                "technical", 65,
                "communication", 65,
                "problemSolving", 65,
                "javaDepth", 65,
                "categories", categories
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCategories(Object rawCategories) {
        LinkedHashSet<String> categories = new LinkedHashSet<>(COMMON_CATEGORIES);
        if (rawCategories instanceof List<?> rawList) {
            for (Object item : rawList) {
                String normalized = normalizeCategory(String.valueOf(item));
                if (!normalized.isBlank()) {
                    categories.add(normalized);
                }
            }
        }
        return categories.stream().limit(8).toList();
    }

    private List<String> fallbackResumeCategories(String resumeText) {
        String inferredRole = inferRoleFromResume(resumeText);
        return categoriesForRole(inferredRole);
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
