package com.javadrill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadrill.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final AppProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final List<String> CATEGORIES = List.of(
            "java_core", "oops", "multithreading", "spring",
            "system_design", "problem_solving", "behavioral"
    );
    public static final Map<String, String> CAT_LABELS = Map.of(
            "java_core", "Java Core",
            "oops", "OOP & Design Patterns",
            "multithreading", "Multithreading & Concurrency",
            "spring", "Spring Boot",
            "system_design", "System Design",
            "problem_solving", "Problem Solving",
            "behavioral", "Behavioral"
    );

    private static final List<String> COMMON_CATEGORIES = List.of("problem_solving", "behavioral");
    private static final Map<String, String> ROLE_LABELS = Map.ofEntries(
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

    public String normalizeRole(String role) {
        if (role == null || role.isBlank()) return "java_developer";
        String normalized = role.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return ROLE_CATEGORIES.containsKey(normalized) ? normalized : "java_developer";
    }

    public String normalizeExperience(String experience) {
        if (experience == null || experience.isBlank()) return "3_5";
        String normalized = experience.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return EXPERIENCE_LABELS.containsKey(normalized) ? normalized : "3_5";
    }

    public String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(normalizeRole(role), "Java Developer");
    }

    public String experienceLabel(String experience) {
        return EXPERIENCE_LABELS.getOrDefault(normalizeExperience(experience), "3-5 years");
    }

    public List<String> categoriesForRole(String role) {
        LinkedHashSet<String> categories = new LinkedHashSet<>(COMMON_CATEGORIES);
        categories.addAll(ROLE_CATEGORIES.getOrDefault(normalizeRole(role), ROLE_CATEGORIES.get("java_developer")));
        return new ArrayList<>(categories);
    }

    public String categoryLabel(String category) {
        if (category == null || category.isBlank()) return "";
        if (CAT_LABELS.containsKey(category)) return CAT_LABELS.get(category);
        String[] parts = category.split("_");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) continue;
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return String.join(" ", words);
    }

    public static class GeminiQuotaException extends RuntimeException {
        public GeminiQuotaException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Core Gemini API call — handles content structure correctly
     */
    public String callGemini(String userPrompt, String systemPrompt) {
        return callGeminiWithTemp(userPrompt, systemPrompt, 0.7);
    }

    public String callGeminiWithTemp(String userPrompt, String systemPrompt, double temperature) {
        try {
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

            String url = props.getGemini().getUrl() + "?key=" + props.getGemini().getApiKey();

            String responseStr = webClientBuilder.build()
                    .post().uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            var json = objectMapper.readTree(responseStr);

            // Check for API errors
            if (json.has("error")) {
                String errMsg = json.get("error").get("message").asText();
                log.error("Gemini API error response: {}", errMsg);
                throw new RuntimeException("Gemini API error: " + errMsg);
            }

            var candidates = json.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("Gemini returned no candidates. Full response: {}", responseStr);
                throw new RuntimeException("No response from AI");
            }

            var parts = candidates.get(0).path("content").path("parts");
            var sb = new StringBuilder();
            for (var part : parts) {
                if (part.has("text")) sb.append(part.get("text").asText());
            }
            String result = sb.toString().trim();
            log.debug("Gemini response ({}chars): {}", result.length(),
                    result.substring(0, Math.min(100, result.length())));
            return result;

        } catch (WebClientResponseException e) {
            if (isQuotaStatus(e.getStatusCode().value()) || isQuotaMessage(e.getResponseBodyAsString())) {
                log.warn("Gemini quota/rate limit reached: status={} body={}",
                        e.getStatusCode().value(), truncate(e.getResponseBodyAsString(), 180));
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.", e);
            }
            throw e;
        } catch (RuntimeException e) {
            if (isQuotaMessage(e.getMessage())) {
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.", e);
            }
            throw e;
        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            throw new RuntimeException("AI service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Parse resume — returns concise summary for question generation
     */
    public String parseResume(String resumeText) {
        String prompt = "Resume text:\n\n" + resumeText.substring(0, Math.min(4000, resumeText.length()))
                + "\n\nExtract and summarize: candidate name, years of experience, "
                + "primary role, technical and business skills, frameworks, tools, projects, "
                + "leadership or HR responsibilities, job titles, and tech stack. "
                + "Be concise, under 250 words. Focus on evidence useful for role-based interview questions.";

        try {
            return callGemini(prompt, "You are a resume parser. Extract technical profile concisely. No fluff.");
        } catch (GeminiQuotaException e) {
            log.warn("Using resume fallback because Gemini quota is exhausted");
            return resumeText.substring(0, Math.min(1200, resumeText.length()));
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
                "Questions must be resume-based: use the candidate's real projects, tools, responsibilities, and tech stack where possible. " +
                "Vary categories across: %s\n" +
                "Problem solving and behavioral are common for every role; other categories must match the chosen role and resume. " +
                "Each question should sound like a real senior interviewer asking it.\n" +
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
                    "You are Sarah, a senior interviewer who can interview software engineers, data roles, managers, architects, product, and HR roles. " +
                    "Generate natural, varied, role-specific interview questions. Return only valid JSON array.", 0.9);
            return safeParseJsonArray(raw);
        } catch (GeminiQuotaException e) {
            log.warn("Gemini quota exhausted while generating questions; using fallback questions");
            return fallbackQuestions(count, role, allowedCategories);
        }
    }

    public List<Map<String, String>> generateCommonQuestions(List<String> existingTexts, int count) {
        String existing = existingTexts.isEmpty() ? "none" : String.join("\n- ", existingTexts);
        String prompt = String.format(
                "Already available common Java interview questions (DO NOT ask similar ones):\n- %s\n\n" +
                "Generate exactly %d reusable COMMON Java interview questions for a shared question bank. " +
                "These questions must be useful for most Java candidates and must NOT mention a candidate resume, " +
                "specific company, specific project, years of experience, personal background, or uploaded profile. " +
                "Prefer evergreen fundamentals, practical Spring Boot, OOP, collections, concurrency, REST, testing, " +
                "system design basics, debugging, and behavioral questions that any Java candidate can answer.\n\n" +
                "Return ONLY valid JSON array:\n" +
                "[{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"medium\"}, ...]\n" +
                "category must be one of: %s\n" +
                "difficulty: easy | medium | hard",
                existing,
                count,
                String.join(", ", CATEGORIES)
        );

        try {
            String raw = callGeminiWithTemp(prompt,
                    "You maintain a reusable Java interview question bank. Return only valid JSON array.", 0.75);
            return safeParseJsonArray(raw);
        } catch (GeminiQuotaException e) {
            log.warn("Gemini quota exhausted while generating common questions; using fallback questions");
            return fallbackQuestions(count, "java_developer", CATEGORIES);
        }
    }

    /**
     * Generate conversational feedback for a single answer.
     * Sounds like a human interviewer, not a bot.
     */
    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer,
                                    String role, String experienceLevel) {
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
                "You are Sarah, a friendly but professional senior interviewer. " +
                "Give short, natural spoken feedback — the kind you'd hear in a real interview room. " +
                "Be specific, not generic. Sound human.");
    }

    /**
     * Score completed interview — returns scores map
     */
    public Map<String, Object> calculateScores(List<Map<String, String>> qaList,
                                               String role,
                                               String experienceLevel,
                                               List<String> allowedCategories) {
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
                    "You are a strict interview evaluator. Score realistically based on answer quality. " +
                    "Do not give inflated scores. Return only valid JSON.", 0.3);
            return safeParseJsonObject(raw);
        } catch (GeminiQuotaException e) {
            log.warn("Gemini quota exhausted while scoring; using fallback scores");
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
}
