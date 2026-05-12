package com.assessarc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.assessarc.config.AppProperties;
import com.assessarc.model.GeminiUsageLog;
import com.assessarc.repository.GeminiUsageRepository;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private volatile GoogleCredentials vertexCredentials;

    private static final String DEFAULT_ROLE = "software_engineer";
    private static final List<String> VERTEX_SCOPES = List.of("https://www.googleapis.com/auth/cloud-platform");
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
            Map.entry("software_engineer",      List.of("programming", "api_design", "databases", "system_design", "testing", "debugging")),
            Map.entry("java_developer",         List.of("java_core", "oops", "multithreading", "spring", "microservices", "system_design")),
            Map.entry("python_developer",       List.of("python_core", "oops", "django_fastapi", "api_design", "databases", "testing")),
            Map.entry("react_developer",        List.of("javascript", "react", "frontend_architecture", "testing", "api_design", "performance")),
            Map.entry("full_stack_developer",   List.of("javascript", "react", "api_design", "databases", "system_design", "cloud_devops")),
            Map.entry("backend_engineer",       List.of("api_design", "databases", "microservices", "system_design", "testing", "cloud_devops")),
            Map.entry("frontend_engineer",      List.of("javascript", "react", "frontend_architecture", "testing", "performance", "accessibility")),
            Map.entry("data_scientist",         List.of("python_core", "statistics", "machine_learning", "sql", "data_modeling", "experimentation")),
            Map.entry("data_engineer",          List.of("python_core", "sql", "data_modeling", "distributed_systems", "cloud_devops", "data_quality")),
            Map.entry("devops_engineer",        List.of("linux", "ci_cd", "cloud_devops", "kubernetes", "observability", "security")),
            Map.entry("cloud_engineer",         List.of("cloud_devops", "system_design", "networking", "security", "kubernetes", "cost_optimization")),
            Map.entry("qa_automation_engineer", List.of("testing", "automation_frameworks", "api_testing", "ci_cd", "debugging", "quality_strategy")),
            Map.entry("mobile_developer",       List.of("mobile_architecture", "ui_state", "api_design", "testing", "performance", "release_management")),
            Map.entry("software_architect",     List.of("architecture", "system_design", "microservices", "cloud_devops", "security", "leadership")),
            Map.entry("engineering_manager",    List.of("people_management", "leadership", "delivery", "hiring", "stakeholder_management", "technical_judgment")),
            Map.entry("product_manager",        List.of("product_strategy", "prioritization", "stakeholder_management", "metrics", "execution", "user_research")),
            Map.entry("hr_recruiter",           List.of("hiring", "sourcing", "employee_relations", "communication", "process_management", "stakeholder_management"))
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
            AppProperties.Vertex vertex = props.getGemini().getVertex();
            if (vertex == null || !vertex.isEnabled()) {
                throw new GeminiUnavailableException("Vertex AI service is not enabled");
            }
            if (vertex.getProjectId() == null || vertex.getProjectId().isBlank()) {
                throw new GeminiUnavailableException("Vertex AI project id is not configured");
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

            String url = vertexGenerateContentUrl(vertex);
            String accessToken = vertexAccessToken(vertex);

            String responseStr = webClientBuilder.build()
                    .post().uri(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();

            /*
             * Previous direct Gemini API transport kept for rollback/reference only.
             *
             * String apiKey = props.getGemini().getApiKey();
             * if (apiKey == null || apiKey.isBlank()) {
             *     throw new GeminiUnavailableException("AI service is not configured");
             * }
             * String url = props.getGemini().getUrl() + "?key=" + apiKey;
             * String responseStr = webClientBuilder.build()
             *         .post().uri(url)
             *         .header("Content-Type", "application/json")
             *         .bodyValue(body)
             *         .retrieve()
             *         .bodyToMono(String.class)
             *         .timeout(Duration.ofSeconds(45))
             *         .block();
             */

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
                - Extract candidate name, years of experience, job titles, tech stack, frameworks, tools, cloud platforms, and notable projects.
                - Be concise and specific — under 300 words. Focus on actual technical skills.

                Category rules:
                - categories must be lower_snake_case
                - Choose 6 to 10 interview categories
                - Categories MUST reflect the candidate's actual specific technical skills from the resume.
                  For example: if resume has Spring Boot → use "spring_boot"; has React → use "react";
                  has Kafka → use "kafka"; has AWS → use "aws"; has Docker/Kubernetes → use "kubernetes";
                  has PostgreSQL → use "postgresql"; has Redis → use "redis"; has GraphQL → use "graphql".
                - Do NOT use only generic categories like "programming" or "api_design" — go deeper.
                - Always include "problem_solving" if the resume shows technical or analytical work.
                - Use "behavioral" for leadership or people-heavy profiles when relevant.
                """.formatted(excerpt);

        try {
            Map<String, Object> raw = safeParseJsonObject(callGemini(
                    prompt,
                    "You are a resume parser for realistic human-like mock interviews. " +
                    "Extract only evidence-backed summary and skill-specific interview categories. " +
                    "Return only JSON.",
                    userId, interviewId, "resume_parse"
            ));
            String summary = String.valueOf(raw.getOrDefault("summary", "")).trim();
            List<String> categories = extractCategories(raw.get("categories"));
            if (summary.isBlank()) summary = fallbackResumeSummary(resumeText);
            if (categories.isEmpty()) categories = fallbackResumeCategories(resumeText);
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
                allowedCategories, 30, userId, interviewId, callType);
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
        String normalizedRole = normalizeRole(role);

        // Determine coding questions
        boolean hasCoding = roleRequiresCoding(role);
        int codingCount = 0;
        String codingInstructions = "";
        if (hasCoding) {
            if (durationMinutes == 30) {
                codingCount = 1;
                codingInstructions = "Include exactly 1 CODING question (difficulty: easy). " +
                        "Make it a real, trending most-asked coding question asked in top tech company(like Google, Amazon, and Microsoft, Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc) interviews — not a trivial print statement.";
            } else if (durationMinutes == 60) {
                codingCount = 2;
                codingInstructions = "Include trending most-asked exactly 2 CODING questions. " +
                        "First: easy — a common interview coding question. " +
                        "Second: medium — a more realistic problem that tests algorithmic thinking. " +
                        "Both should reflect trending questions asked at companies like Google, Amazon, and Microsoft, Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc.";
            }
        }
        int textCount = count - codingCount;

        // ── Build bucket counts ──
        int fundamentalsCount;
        int trickyCount;
        int scenarioCount;
        int projectCount;
        int behavioralCount;

        if (fresher) {
            fundamentalsCount = Math.round(textCount * 0.40f);
            trickyCount       = Math.round(textCount * 0.15f);
            scenarioCount     = Math.round(textCount * 0.25f);
            projectCount      = Math.round(textCount * 0.10f);
            behavioralCount   = textCount - fundamentalsCount - trickyCount - scenarioCount - projectCount;
        } else {
            fundamentalsCount = Math.round(textCount * 0.20f);
            trickyCount       = Math.round(textCount * 0.15f);
            scenarioCount     = Math.round(textCount * 0.25f);
            projectCount      = Math.round(textCount * 0.25f);
            behavioralCount   = textCount - fundamentalsCount - trickyCount - scenarioCount - projectCount;
        }

        String depthInstructions = buildDepthInstructions(normalizedRole, fresher, expLabel,
                textCount, fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount);

        String prompt = String.format(
                "Target interview role: %s\n" +
                "Experience level: %s\n\n" +
                "Candidate profile from resume (use this to personalize questions — reference actual skills, tools, and projects from the resume where relevant):\n%s\n\n" +
                "Already asked questions (DO NOT repeat or ask similar ones):\n- %s\n\n" +
                "%s\n\n" +
                "Generate exactly %d TEXT questions and %d CODING questions.\n" +
                "Allowed categories: %s\n\n" +
                "%s\n\n" +
                "IMPORTANT — QUESTION QUALITY RULES:\n" +
                "- 40% Include trending, most-asked questions from top tech companies Google, Amazon, Microsoft, Flipkart, Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc. relevant to this role and experience level.\n" +
                "- Each question must sound like a real human interviewer saying it out loud — natural, conversational, not robotic.\n" +
                "- Mix question types: fundamentals, tricky/gotcha, scenario-based, resume/project-based, and behavioral — as per the bucket distribution above.\n" +
                "- DO NOT mention 'resume', 'your profile', 'as per your CV' directly in the question text.\n" +
                "- No bullet-style or list-style questions (e.g. avoid 'List the types of...' — ask it conversationally instead).\n" +
                "- No repetitive openers across questions — vary them broadly.\n" +
                "- Prioritize questions that are actually asked in real interviews at top tech companies for this role.\n\n" +
                "Return ONLY a valid JSON array:\n" +
                "[{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"medium\",\"type\":\"text\"}, ...]\n\n" +
                "For CODING questions, include:\n" +
                "{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"easy\",\"type\":\"coding\",\"codingData\":{\"language\":\"%s\",\"expectedOutput\":\"...\",\"testCases\":[{\"input\":\"...\",\"expectedOutput\":\"...\"}],\"description\":\"...\"}}\n\n" +
                "CODING formatting rules:\n" +
                "- question must be ONLY a short title/instruction, one sentence max.\n" +
                "- Do NOT put labels like Problem Description, Expected Output, Examples, or Test Cases inside question.\n" +
                "- Put the full statement only in codingData.description.\n" +
                "- Put every sample input/output only in codingData.testCases.\n" +
                "- Put the return/output requirement only in codingData.expectedOutput.\n\n" +
                "Rules:\n" +
                "- category must be one of: %s\n" +
                "- difficulty: easy | medium | hard (NEVER hard for coding)\n" +
                "- type: text | coding",
                roleLabel, expLabel, resumeSummary, existing,
                depthInstructions,
                textCount, codingCount, allowed, codingInstructions,
                codingLanguageForRole(normalizedRole),
                allowed
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

    // ── Depth Instructions Builder (all roles) ──

    private String buildDepthInstructions(String role, boolean fresher, String expLabel,
                                           int textCount, int fundamentalsCount, int trickyCount,
                                           int scenarioCount, int projectCount, int behavioralCount) {
        String roleExamples = getRoleBucketExamples(role, fresher);

        if (fresher) {
            return String.format("""
                    This candidate is a FRESHER or very early in their career.
                    They have limited or no real production experience — only college projects, coursework, or self-learning.
                    DO NOT ask about production systems, team leadership, scaling decisions, or things requiring years of experience.

                    You MUST generate questions spread across these 5 buckets (distribution below):

                    BUCKET 1 — FUNDAMENTALS (%d questions):
                    Test raw conceptual knowledge. Phrased conversationally, not like a textbook.
                    Ask "how does X actually work", "what's the difference between X and Y", "what happens when".
                    These must be trending fundamentals asked at top tech companies for this role.

                    BUCKET 2 — TRICKY / GOTCHA (%d questions):
                    Deceptively simple questions that reveal whether they truly understand, or just memorized.
                    Common interview trap questions for this role and tech stack.

                    BUCKET 3 — SIMPLE SCENARIO / IMAGINE-YOU-ARE-BUILDING (%d questions):
                    Small hypothetical scenarios. College-project-level scope. No production or scaling.
                    "Imagine you're building...", "If you had to design a small...", "What would you do if..."

                    BUCKET 4 — PROJECT / EXPERIENCE BASED (%d questions):
                    Ask about their own projects (college projects are fine). Probe technical depth.
                    Reference actual skills/projects from their resume naturally.

                    BUCKET 5 — BEHAVIORAL / CURIOSITY (%d questions):
                    How they think, how they learn, how they handle challenges.
                    "When you get stuck...", "Is there something in [tech] you tried recently..."

                    Role-specific example questions to guide your tone and style (DO NOT copy verbatim — use as reference only):
                    %s

                    STRICT RULES:
                    - Avoid "in your current role", "in production", "your team", "scaling to millions"
                    - Vary openers across all questions — no two questions should start the same way
                    - Warm and encouraging tone — campus-style interview feel
                    - Mix the buckets in the output array (do not cluster them)
                    - Include trending questions actually asked in tech company interviews for this role
                    """,
                    fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount,
                    roleExamples);
        } else {
            return String.format("""
                    This is an EXPERIENCED candidate (%s experience).
                    Ask questions that test real depth, tradeoffs, ownership, and battle-tested judgment.

                    You MUST generate questions spread across these 5 buckets (distribution below):

                    BUCKET 1 — DEEP FUNDAMENTALS / INTERNALS (%d questions):
                    Not textbook definitions — test real depth and internals. "How does X actually work under the hood?"
                    Include trending deep-dive questions asked at top tech companies for this role.

                    BUCKET 2 — TRICKY / GOTCHA (%d questions):
                    Questions even experienced engineers get wrong or oversimplify.
                    Common interview traps and edge cases for this role and tech stack.

                    BUCKET 3 — DESIGN / SCENARIO / TRADEOFF (%d questions):
                    Real engineering decisions. Force them to justify choices.
                    "How would you design...", "What tradeoff would you make...", "When would you choose X over Y?"
                    Reflect trending system design and scenario questions from FAANG-style interviews.

                    BUCKET 4 — RESUME / PROJECT DEPTH (%d questions):
                    Dig into their actual work — reference real skills and projects from their resume.
                    Ask follow-ups that expose whether they own it or just participated.
                    "Walk me through a time you...", "What would you do differently..."

                    BUCKET 5 — BEHAVIORAL / LEADERSHIP (%d questions):
                    Test judgment, conflict resolution, and engineering maturity.
                    "Tell me about a time...", "How do you decide...", "Your team disagrees..."

                    Role-specific example questions to guide your tone and style (DO NOT copy verbatim — use as reference only):
                    %s

                    STRICT RULES:
                    - Probe for OWNERSHIP not just participation ("you" not just "your team")
                    - Never ask generic definitions — test application and real judgment
                    - Vary openers radically across all questions
                    - Mix the buckets in the output array (do not cluster them)
                    - Include trending questions actually asked in real interviews at top tech companies for this role
                    """,
                    expLabel,
                    fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount,
                    roleExamples);
        }
    }

    // ── Role-Specific Bucket Examples (all 17 roles) ──

    private String getRoleBucketExamples(String role, boolean fresher) {
        return switch (role) {

            case "java_developer" -> fresher ? """
                FUNDAMENTALS: "What actually happens in memory when you create an object in Java — walk me through it."
                FUNDAMENTALS: "Can you explain the difference between == and .equals() in Java, and when would == fool you?"
                FUNDAMENTALS: "How does HashMap handle two keys that produce the same hash code?"
                FUNDAMENTALS: "What's the difference between checked and unchecked exceptions — when would you use each?"
                TRICKY: "Can you have a try block without a catch block in Java? What would that look like?"
                TRICKY: "Is String a primitive in Java? What makes it behave differently from int?"
                SCENARIO: "Imagine you're building a simple student management REST API — how would you structure the endpoints?"
                SCENARIO: "You have a list of 1000 user objects and need to find all users above age 25 — how would you do it in Java 8+?"
                PROJECT: "Tell me about a Java project you built — what was the hardest bug you ran into?"
                BEHAVIORAL: "When you get stuck on a Java problem, what's your actual process for figuring it out?"
                """ : """
                FUNDAMENTALS: "How does the JVM decide when to promote an object from young gen to old gen — and what have you seen go wrong with that?"
                FUNDAMENTALS: "How does Spring's @Transactional actually work — what happens if you call a @Transactional method from within the same class?"
                FUNDAMENTALS: "Walk me through what happens to a request between hitting your load balancer and a DB query executing."
                TRICKY: "If you use @Cacheable in Spring and two threads request the same uncached key simultaneously, what happens?"
                TRICKY: "Can a transaction span two microservices? What actually happens if you try?"
                TRICKY: "If you set a Kafka consumer group with 10 partitions and 12 consumers, what happens to the extra two?"
                DESIGN: "How would you design a rate limiter for an API handling 50,000 requests per second?"
                DESIGN: "When would you choose an event-driven architecture over a synchronous REST call — what's your decision framework?"
                PROJECT: "Walk me through a production issue you debugged in a Spring Boot app — what was your investigation process?"
                BEHAVIORAL: "Tell me about a time you disagreed with your tech lead's architecture decision — what did you do?"
                """;

            case "python_developer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a list and a tuple in Python — not just that one is mutable, but when would you actually choose one over the other?"
                FUNDAMENTALS: "What does a decorator actually do under the hood — can you walk me through it step by step?"
                FUNDAMENTALS: "How does Python handle memory management — what's reference counting and when does it not work?"
                TRICKY: "What happens if you use a mutable default argument in a Python function — can you show me an example of why that's dangerous?"
                TRICKY: "Is everything in Python an object? What does that actually mean practically?"
                SCENARIO: "Imagine you're building a REST API with FastAPI — how would you handle validation of incoming request data?"
                SCENARIO: "You need to read a CSV file with 100,000 rows — what's the memory-efficient way to do it in Python?"
                PROJECT: "Tell me about a Python project you built — what libraries did you use and why?"
                BEHAVIORAL: "Is there something in Python you tried recently that surprised you or confused you at first?"
                """ : """
                FUNDAMENTALS: "How does Python's GIL actually work — when does it matter and when doesn't it?"
                FUNDAMENTALS: "What's the difference between multiprocessing and threading in Python — give me a scenario where you'd pick each one."
                TRICKY: "If you have a generator and you iterate it twice, what happens the second time — and why?"
                TRICKY: "What's the difference between __str__ and __repr__ — when does each get called automatically?"
                DESIGN: "How would you design a background job system in Python that's reliable even if workers crash mid-job?"
                DESIGN: "Your Django app is getting slow under load — walk me through how you'd diagnose and fix it."
                PROJECT: "Tell me about a Python service you owned in production — what was the most painful scaling issue you hit?"
                BEHAVIORAL: "Tell me about a time you had to choose between writing clean Python code and shipping fast — what did you decide?"
                """;

            case "react_developer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between state and props in React — when would changing one re-render a component and not the other?"
                FUNDAMENTALS: "What does useEffect actually do — what happens if you forget the dependency array?"
                FUNDAMENTALS: "What is the virtual DOM and why does React use it instead of updating the real DOM directly?"
                TRICKY: "If you call setState twice in a row inside the same function, does the component re-render twice?"
                TRICKY: "Can you use a hook inside an if statement? Why or why not?"
                SCENARIO: "Imagine you're building a search bar that calls an API on every keystroke — what problem does that create and how do you fix it?"
                SCENARIO: "You need to share data between two sibling components — how would you approach that?"
                PROJECT: "Walk me through a React project you built — how did you manage state?"
                BEHAVIORAL: "What's something about React that confused you at first but now makes sense?"
                """ : """
                FUNDAMENTALS: "How does React's reconciliation algorithm decide what to re-render — and how does the key prop affect that?"
                FUNDAMENTALS: "What's the difference between useMemo and useCallback — give me a real case where each one actually helps performance?"
                TRICKY: "When does a useEffect cleanup function run exactly — give me a case where forgetting it causes a real bug."
                TRICKY: "Why can stale closures in useEffect be a problem — how do you reproduce and fix one?"
                DESIGN: "How would you architect state management for a large React app — when does Redux make sense vs Context API vs Zustand?"
                DESIGN: "Your React app is slow on initial load — walk me through your optimization process."
                PROJECT: "Tell me about a performance problem you hit in a React app and how you diagnosed it."
                BEHAVIORAL: "Tell me about a time a frontend bug was much harder to debug than you expected — what made it hard?"
                """;

            case "full_stack_developer" -> fresher ? """
                FUNDAMENTALS: "Walk me through what happens from the moment a user types a URL in the browser to when the page loads."
                FUNDAMENTALS: "What's the difference between a REST API and GraphQL — when would you use one over the other?"
                FUNDAMENTALS: "What is CORS and why does it exist — have you run into a CORS issue in a project?"
                TRICKY: "If your frontend sends a POST request but the server returns a 200 with an error message in the body, how does your frontend know something went wrong?"
                SCENARIO: "You're building a simple e-commerce app — how would you split responsibilities between the frontend and backend?"
                SCENARIO: "Your API is slow and your React app feels sluggish — where do you start debugging?"
                PROJECT: "Tell me about a full-stack project you built — what was the trickiest integration between frontend and backend?"
                BEHAVIORAL: "What part of full-stack development do you find more interesting — frontend or backend — and why?"
                """ : """
                FUNDAMENTALS: "How does session management work in a stateless REST API — walk me through the full flow with JWTs."
                FUNDAMENTALS: "What's the N+1 query problem and how do you catch and fix it in a full-stack app?"
                TRICKY: "You have a React frontend calling a Node/Java backend — where exactly does an auth token get validated, and what happens if the token expires mid-session?"
                DESIGN: "How would you design the architecture for a real-time collaborative document editor — like Google Docs?"
                DESIGN: "Your full-stack app needs to handle file uploads up to 500MB — how do you design that end-to-end?"
                PROJECT: "Walk me through a full-stack feature you owned from design to deployment — what would you do differently?"
                BEHAVIORAL: "Tell me about a time a frontend change broke something in the backend unexpectedly — how did you handle it?"
                """;

            case "backend_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between SQL and NoSQL databases — when would you pick one over the other?"
                FUNDAMENTALS: "What does it mean for an API to be RESTful — can you walk me through the key principles?"
                FUNDAMENTALS: "What is an index in a database and why does it make queries faster?"
                TRICKY: "What happens if two users try to update the same database row at exactly the same time?"
                SCENARIO: "You're building a URL shortener service — how would you design the backend?"
                SCENARIO: "Your API endpoint is returning data slowly — where do you start looking?"
                PROJECT: "Tell me about a backend project you built — how did you handle data storage?"
                BEHAVIORAL: "What's something about backend development that surprised you when you first started learning it?"
                """ : """
                FUNDAMENTALS: "How does database connection pooling work — what happens when all connections are in use?"
                FUNDAMENTALS: "Walk me through how you would implement idempotency for a payment API endpoint."
                TRICKY: "What's the difference between optimistic and pessimistic locking — give me a real scenario where optimistic locking completely fails?"
                TRICKY: "Can you have a distributed transaction across two microservices? What actually happens if one fails halfway?"
                DESIGN: "How would you design a notification service that sends email, SMS, and push — ensuring one failing channel doesn't block others?"
                DESIGN: "How do you design an API that's backward-compatible when you need to make a breaking change?"
                PROJECT: "Tell me about a production incident you dealt with on a backend service — what was your debugging process?"
                BEHAVIORAL: "How do you decide when tech debt is worth fixing now versus deferring?"
                """;

            case "frontend_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between display:none, visibility:hidden, and opacity:0 in CSS — when would you use each?"
                FUNDAMENTALS: "What does 'semantic HTML' mean and why does it matter?"
                FUNDAMENTALS: "How does the browser render a webpage — what's the critical rendering path?"
                TRICKY: "What is event bubbling in JavaScript — can you give me an example where it causes an unexpected behavior?"
                SCENARIO: "You need to build a responsive navigation menu that works on mobile and desktop — how do you approach it?"
                SCENARIO: "Your webpage is loading slowly — what are the first three things you check?"
                PROJECT: "Walk me through a frontend project you built — how did you handle responsiveness?"
                BEHAVIORAL: "What's a CSS or JS behavior that confused you at first but now you understand well?"
                """ : """
                FUNDAMENTALS: "What is the browser's event loop — how does it relate to async/await and Promises?"
                FUNDAMENTALS: "How does CSS specificity work — walk me through a case where specificity caused a hard-to-debug styling issue."
                TRICKY: "What's the difference between debounce and throttle — when does using the wrong one cause a real problem?"
                TRICKY: "What are Web Workers and when would you actually use one?"
                DESIGN: "How would you architect a design system for a large frontend app that multiple teams contribute to?"
                DESIGN: "Your Core Web Vitals scores are poor — walk me through how you'd diagnose and improve LCP, FID, and CLS."
                PROJECT: "Tell me about a complex UI component you built — what were the edge cases you had to handle?"
                BEHAVIORAL: "Tell me about a time a cross-browser compatibility issue caused a real problem — how did you find and fix it?"
                """;

            case "software_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a stack and a queue — when would you use each one?"
                FUNDAMENTALS: "What does time complexity mean — can you walk me through the difference between O(n) and O(n²) with a real example?"
                FUNDAMENTALS: "What is a null pointer and why is it such a common bug?"
                TRICKY: "What's the difference between pass-by-value and pass-by-reference — does Java do one or both?"
                SCENARIO: "If you had to design a parking lot management system, what objects and classes would you model?"
                SCENARIO: "You have a bug in your code but you don't know where it is — walk me through your debugging process."
                PROJECT: "Tell me about a program you wrote from scratch — what made it technically interesting?"
                BEHAVIORAL: "What's a programming concept that took you a while to really understand — how did it finally click?"
                """ : """
                FUNDAMENTALS: "Walk me through how you approach designing a class hierarchy — what signals tell you to use inheritance vs composition?"
                FUNDAMENTALS: "What's the difference between a process and a thread — at the OS level, what resources does each one own?"
                TRICKY: "What are the SOLID principles — give me a real case where violating one caused a real maintenance problem."
                DESIGN: "How would you design a job scheduling system that can run millions of tasks reliably?"
                DESIGN: "You need to build a cache layer for a high-read service — what tradeoffs do you consider?"
                PROJECT: "Walk me through the most technically complex piece of software you've built — what made it hard?"
                BEHAVIORAL: "Tell me about a time you had to refactor a large piece of legacy code — how did you approach it safely?"
                """;

            case "data_scientist" -> fresher ? """
                FUNDAMENTALS: "What's the difference between supervised and unsupervised learning — give me a real example of each."
                FUNDAMENTALS: "What does overfitting mean — how would you know if your model is doing it?"
                FUNDAMENTALS: "Why do we split data into train and test sets — what goes wrong if you don't?"
                TRICKY: "If your model has 99% accuracy, is it necessarily a good model? When would that be a red flag?"
                TRICKY: "What's the difference between correlation and causation — give me an example where confusing them would be dangerous?"
                SCENARIO: "You've trained a model that performs great in testing but terribly in production — where do you start looking?"
                SCENARIO: "You have a dataset with 30% missing values — how do you decide what to do with them?"
                PROJECT: "Walk me through a data analysis or ML project you did — what was your feature engineering process?"
                BEHAVIORAL: "What's a machine learning concept that surprised you once you understood it deeply?"
                """ : """
                FUNDAMENTALS: "How does gradient boosting actually work — what's it doing differently from a single decision tree or random forest?"
                FUNDAMENTALS: "What's the bias-variance tradeoff and how do you tune for it in practice — not just theoretically?"
                TRICKY: "When would you NOT use cross-validation — give me a specific scenario."
                TRICKY: "Can feature scaling hurt a tree-based model? Why or why not?"
                DESIGN: "How would you design an A/B test for a new recommendation algorithm — what are the ways your results could be invalid?"
                DESIGN: "Your model's performance degrades over time in production — how do you build a system to detect and respond to that?"
                PROJECT: "Tell me about a model you deployed to production — what was harder than you expected?"
                BEHAVIORAL: "Tell me about a time your model was technically correct but the business stakeholders pushed back — how did you handle it?"
                """;

            case "data_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a data warehouse and a data lake — when would you use each?"
                FUNDAMENTALS: "What is an ETL pipeline — can you walk me through what each step does?"
                FUNDAMENTALS: "What's the difference between batch processing and stream processing?"
                TRICKY: "If your ETL job fails halfway through, what happens to the data that was already processed?"
                SCENARIO: "You need to move 10 million records from a MySQL database to a data warehouse daily — how do you approach that?"
                SCENARIO: "Your pipeline is running slower than expected — what are the first things you check?"
                PROJECT: "Walk me through a data pipeline you built — what tools did you use and why?"
                BEHAVIORAL: "What's something about data engineering you found much harder in practice than in theory?"
                """ : """
                FUNDAMENTALS: "How does Apache Spark handle data partitioning — what happens when your partitions are skewed?"
                FUNDAMENTALS: "What's the difference between Kafka and a traditional message queue like RabbitMQ — when does Kafka's model break down?"
                TRICKY: "What does 'exactly-once semantics' mean in a streaming pipeline — why is it hard to achieve?"
                TRICKY: "What's a data pipeline anti-pattern you've seen that looked fine but caused real problems at scale?"
                DESIGN: "How would you design a real-time analytics pipeline that needs to handle 100,000 events per second?"
                DESIGN: "How do you ensure data quality across a complex pipeline with multiple transformation stages?"
                PROJECT: "Tell me about the most complex data pipeline you've built — what were the failure modes and how did you handle them?"
                BEHAVIORAL: "Tell me about a time a data pipeline failure caused a downstream impact — how did you manage it?"
                """;

            case "devops_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a Docker image and a container — can you walk me through it?"
                FUNDAMENTALS: "What does a CI/CD pipeline actually do — can you walk me through the stages?"
                FUNDAMENTALS: "What is a Kubernetes pod — how is it different from a Docker container?"
                TRICKY: "If two containers are in the same Kubernetes pod, can they share the same port?"
                TRICKY: "What happens to running containers if the Docker daemon crashes?"
                SCENARIO: "Your deployment just failed in production — what's your first step?"
                SCENARIO: "You need to set up a CI pipeline for a Java application — what stages would you include?"
                PROJECT: "Walk me through a CI/CD pipeline you set up — what tools did you use?"
                BEHAVIORAL: "What's a DevOps concept that was confusing at first but now makes complete sense to you?"
                """ : """
                FUNDAMENTALS: "How does Kubernetes decide which node to schedule a pod on — what factors does the scheduler consider?"
                FUNDAMENTALS: "What actually happens during a rolling deployment in Kubernetes — at the networking and pod lifecycle level?"
                TRICKY: "If a Kubernetes liveness probe fails, what happens exactly — how is that different from a readiness probe failing?"
                TRICKY: "Can two services in different Kubernetes namespaces talk to each other — how, and what are the security implications?"
                DESIGN: "How would you design a zero-downtime deployment pipeline for a stateful service?"
                DESIGN: "Your Kubernetes cluster is running out of resources unpredictably — how do you investigate and fix that?"
                PROJECT: "Tell me about a production outage you handled — what was your incident response process?"
                BEHAVIORAL: "Tell me about a time you automated something that was previously done manually — what was the impact?"
                """;

            case "cloud_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between IaaS, PaaS, and SaaS — can you give a real example of each?"
                FUNDAMENTALS: "What is a VPC and why do you need one?"
                FUNDAMENTALS: "What's the difference between horizontal and vertical scaling?"
                TRICKY: "If your EC2 instance runs out of disk space, does adding more RAM help? Why or why not?"
                SCENARIO: "You need to host a simple web application on AWS — what services would you use and why?"
                SCENARIO: "Your cloud bill is unexpectedly high this month — how do you investigate what's causing it?"
                PROJECT: "Have you set up anything on AWS, GCP, or Azure — walk me through what you built."
                BEHAVIORAL: "What's a cloud concept that surprised you once you actually started working with it?"
                """ : """
                FUNDAMENTALS: "How does AWS Auto Scaling decide when to add or remove instances — what are the failure modes of that decision?"
                FUNDAMENTALS: "What's the difference between AWS SQS and SNS — when would you use them together?"
                TRICKY: "Your Lambda function is timing out intermittently but only under load — where do you start?"
                TRICKY: "What's a common cloud architecture pattern that looks good on paper but fails badly in practice?"
                DESIGN: "How would you design a multi-region active-active architecture for a high-availability service?"
                DESIGN: "How do you architect for cost efficiency without sacrificing reliability in a cloud-native app?"
                PROJECT: "Walk me through a cloud infrastructure you designed from scratch — what tradeoffs did you make?"
                BEHAVIORAL: "Tell me about a cloud cost or reliability issue you identified and fixed — what was the impact?"
                """;

            case "qa_automation_engineer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between manual testing and automated testing — when would you choose one over the other?"
                FUNDAMENTALS: "What's the difference between a test case and a test suite?"
                FUNDAMENTALS: "What is a regression test — why does it matter?"
                TRICKY: "Can 100% test coverage guarantee your software has no bugs? Why or why not?"
                SCENARIO: "You need to write automated tests for a login form — what test cases would you write?"
                SCENARIO: "A bug was reported in production that your tests didn't catch — what's your first reaction?"
                PROJECT: "Have you written automated tests in any project — what framework did you use?"
                BEHAVIORAL: "What's something about software testing that you found more nuanced than you expected?"
                """ : """
                FUNDAMENTALS: "How does Selenium WebDriver communicate with a browser — what actually happens under the hood?"
                FUNDAMENTALS: "What's the difference between black-box and white-box testing — when does each one catch bugs the other misses?"
                TRICKY: "What's a flaky test and how do you systematically diagnose and eliminate one?"
                TRICKY: "When should you NOT automate a test case — what signals tell you manual is better?"
                DESIGN: "How would you design a test automation framework from scratch for a large microservices application?"
                DESIGN: "How do you build a quality strategy that keeps up with a fast-moving agile team without blocking releases?"
                PROJECT: "Tell me about an automation framework you built or significantly improved — what was the before and after?"
                BEHAVIORAL: "Tell me about a time you caught a critical bug that others missed — how did you find it?"
                """;

            case "mobile_developer" -> fresher ? """
                FUNDAMENTALS: "What's the difference between Android and iOS development — at a platform level?"
                FUNDAMENTALS: "What is an Activity lifecycle in Android (or ViewController lifecycle in iOS) — why does it matter?"
                FUNDAMENTALS: "What's the difference between running code on the main thread vs a background thread in mobile?"
                TRICKY: "What happens to your app's state when the user rotates the screen on Android?"
                SCENARIO: "You're building a simple todo app for mobile — how do you persist data locally?"
                SCENARIO: "Your app is consuming too much battery — where do you start investigating?"
                PROJECT: "Walk me through a mobile app you built — what was the trickiest part?"
                BEHAVIORAL: "What's something about mobile development that's different from what you expected?"
                """ : """
                FUNDAMENTALS: "How does Android's Jetpack Compose (or SwiftUI) differ from the traditional View system — what are the real tradeoffs?"
                FUNDAMENTALS: "What is a memory leak in mobile apps — what are the most common causes in Android or iOS?"
                TRICKY: "If your API call is running on a background thread and you try to update the UI from it, what happens — and how do you fix it?"
                TRICKY: "What's the difference between a cold start and a warm start — how do you optimize each?"
                DESIGN: "How would you architect offline support in a mobile app that syncs with a backend?"
                DESIGN: "How do you design a mobile release process that lets you ship fast without breaking production?"
                PROJECT: "Tell me about the most technically challenging feature you shipped in a mobile app — what made it hard?"
                BEHAVIORAL: "Tell me about a time a mobile app crashed in production — how did you diagnose and fix it?"
                """;

            case "software_architect" -> fresher ? """
                FUNDAMENTALS: "What's the difference between monolithic and microservices architecture — when would you choose one?"
                FUNDAMENTALS: "What does 'separation of concerns' mean in software design?"
                TRICKY: "Can microservices make things worse? Give me a case where they would."
                SCENARIO: "If you were designing a simple e-commerce backend, how would you think about splitting it into services?"
                PROJECT: "Walk me through the architecture of the most complex system you've worked on."
                BEHAVIORAL: "What architectural decision are you most proud of — and what would you do differently?"
                """ : """
                FUNDAMENTALS: "How do you decide the right boundaries for a microservice — what signals tell you a service is too big or too small?"
                FUNDAMENTALS: "What's the CAP theorem and when have you had to consciously make a CAP tradeoff in a real system?"
                TRICKY: "What are the failure modes of an event-driven architecture that synchronous systems don't have?"
                DESIGN: "How would you migrate a large monolith to microservices without taking the whole system down?"
                DESIGN: "How do you architect for observability — what does a good monitoring and alerting strategy look like?"
                PROJECT: "Walk me through the most consequential architectural decision you've made — what was the long-term impact?"
                BEHAVIORAL: "Tell me about a time your architectural recommendation was rejected — how did you handle it?"
                """;

            case "engineering_manager" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a manager's role and a tech lead's role?"
                FUNDAMENTALS: "How do you think about prioritizing work across a team when everything feels urgent?"
                TRICKY: "If two senior engineers on your team disagree on an architecture decision, what do you do?"
                SCENARIO: "Imagine your team is going to miss a deadline — how do you handle the conversation with stakeholders?"
                PROJECT: "Walk me through a time you coordinated a project across multiple people."
                BEHAVIORAL: "How do you give feedback to someone who gets defensive?"
                """ : """
                FUNDAMENTALS: "How do you decide when a project needs more engineers versus better process or tooling?"
                FUNDAMENTALS: "What does 'psychological safety' actually mean in practice — how do you build it deliberately?"
                TRICKY: "Your best engineer wants to rewrite the entire codebase. How do you handle that conversation?"
                TRICKY: "You have a high performer who is toxic to the team's culture. What do you do?"
                DESIGN: "How would you structure a hiring process that filters for engineering judgment, not just coding speed?"
                DESIGN: "How do you set up a team's on-call process that's sustainable and doesn't burn people out?"
                PROJECT: "Tell me about a time a project you were managing went off the rails — how did you recover it?"
                BEHAVIORAL: "Tell me about a time you had to let someone go — how did you handle it?"
                """;

            case "product_manager" -> fresher ? """
                FUNDAMENTALS: "What's the difference between a product manager and a project manager?"
                FUNDAMENTALS: "How do you decide what features to build when you have more requests than capacity?"
                TRICKY: "A feature has high user demand but low business value — do you build it? How do you decide?"
                SCENARIO: "Walk me through how you would approach building a roadmap for a new product from scratch."
                PROJECT: "Tell me about a product decision you made — how did you validate it was the right one?"
                BEHAVIORAL: "Tell me about a time engineering pushed back on your requirements — how did you handle it?"
                """ : """
                FUNDAMENTALS: "How do you balance short-term user needs against long-term strategic direction in a roadmap?"
                FUNDAMENTALS: "What metrics do you use to know if a feature you shipped was actually successful?"
                TRICKY: "How do you make a prioritization decision when the data and stakeholder pressure point in opposite directions?"
                DESIGN: "How would you design a go-to-market strategy for a new B2B product entering a crowded market?"
                DESIGN: "Walk me through your process for running an effective product discovery sprint."
                PROJECT: "Tell me about a product bet that didn't work out — what did you learn and what would you do differently?"
                BEHAVIORAL: "Tell me about a time you had to kill a feature or product that people had invested in — how did you manage that?"
                """;

            case "hr_recruiter" -> fresher ? """
                FUNDAMENTALS: "What's the difference between sourcing and recruiting?"
                FUNDAMENTALS: "How do you evaluate a candidate's cultural fit without being biased?"
                TRICKY: "A strong candidate accepts your offer and then backs out the day before joining — what do you do?"
                SCENARIO: "You need to fill a senior Java developer role in 30 days — how do you approach it?"
                PROJECT: "Walk me through a hiring process you've been part of — what worked and what didn't?"
                BEHAVIORAL: "Tell me about a time you had a difficult conversation with a hiring manager — how did you handle it?"
                """ : """
                FUNDAMENTALS: "How do you build a talent pipeline for roles that are hard to fill — what's your sourcing strategy?"
                FUNDAMENTALS: "How do you reduce bias in a hiring process without slowing it down?"
                TRICKY: "How do you handle a situation where the hiring manager's bar is so high that you're rejecting good candidates?"
                DESIGN: "How would you redesign an interview process that has a high offer rejection rate?"
                DESIGN: "How do you build an employer brand strategy that attracts passive candidates?"
                PROJECT: "Tell me about a critical hire you made that had a big impact — what was your process?"
                BEHAVIORAL: "Tell me about a time you pushed back on a hiring decision you disagreed with — what happened?"
                """;

            // ── Generic fallback for any unmapped role ──
            default -> fresher ? """
                FUNDAMENTALS: "Can you walk me through how [core concept in your area] actually works — not the textbook definition, but how you understand it?"
                FUNDAMENTALS: "What's the difference between X and Y in your field — when would you choose one over the other?"
                TRICKY: "Here's a common assumption people make about [topic] — is it always true? When does it break?"
                SCENARIO: "Imagine you're building a simple version of [common problem in this field] from scratch — how would you start?"
                PROJECT: "Tell me about a project you worked on that you learned the most from — what was the hardest part?"
                BEHAVIORAL: "When you run into something in your field that you don't understand, what's your process for figuring it out?"
                """ : """
                FUNDAMENTALS: "How does [core technology or concept] actually behave when [edge case or pressure] — what have you seen go wrong?"
                TRICKY: "Give me a case where the obvious approach to [common problem] completely falls apart in practice."
                DESIGN: "How would you design [common system in this role] to handle [realistic constraint] — walk me through your thinking."
                PROJECT: "Tell me about the most impactful thing you've built or delivered — what was the decision that made the biggest difference?"
                BEHAVIORAL: "Tell me about a time you disagreed with a technical or strategic decision — what did you do and what was the outcome?"
                """;
        };
    }

    // ── Coding Language Helper ──

    private String codingLanguageForRole(String normalizedRole) {
        return switch (normalizedRole) {
            case "java_developer", "backend_engineer", "software_architect" -> "java";
            case "python_developer", "data_scientist", "data_engineer"      -> "python";
            case "react_developer", "frontend_engineer"                     -> "javascript";
            case "full_stack_developer"                                      -> "javascript";
            case "mobile_developer"                                          -> "kotlin";
            default                                                          -> "java";
        };
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

        String feedbackToneGuide = fresher
                ? """
                  This is a fresher candidate. Be warm, encouraging, and patient.
                  Acknowledge what they got right first, even if partial.
                  Give ONE clear thing to improve, but frame it gently — like a mentor, not a critic.
                  Example tone: "That's a good start — you've got the concept right. One thing to add would be..."
                  """
                : """
                  This is an experienced candidate. Be professional and direct.
                  Acknowledge what was good, be specific about gaps or missed depth.
                  Treat them as a peer — skip over-praise, give honest calibration.
                  Example tone: "Good answer on X. Where I'd push you is on the tradeoff around Y — in production, that matters because..."
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

    public Map<String, Object> calculateScores(List<Map<String, String>> qaList, String role, String experienceLevel,
        List<String> allowedCategories, String userId, String interviewId) {
      boolean fresher = isFresher(experienceLevel);
      String roleLabel = roleLabel(role);

      var sb = new StringBuilder();

      // ── Per-answer block ──
      sb.append("You are evaluating a mock interview for a ").append(roleLabel).append(" candidate.\n");
      sb.append("Experience level: ").append(experienceLabel(experienceLevel)).append("\n\n");
      sb.append("Below are the interview questions and the candidate's actual answers.\n");
      sb.append("Read each answer carefully and judge it STRICTLY on its own merit.\n\n");
      sb.append("=== INTERVIEW RESPONSES ===\n\n");

      int qNum = 1;
      for (var qa : qaList) {
        String catLabel = categoryLabel(qa.get("category"));
        String answer = qa.getOrDefault("answer", "");
        String answerText = (answer == null || answer.isBlank()) ? "(no answer given — candidate was silent or skipped)"
            : answer.trim();
        sb.append("Q").append(qNum).append(" [").append(catLabel).append("]: ").append(qa.get("question")).append("\n");
        sb.append("Answer: ").append(answerText).append("\n\n");
        qNum++;
      }

      // ── Scoring rubric with hard anchors ──
      sb.append("=== SCORING RUBRIC (MANDATORY — READ BEFORE SCORING) ===\n\n");
      sb.append("Score range is 0–100. Use the FULL range. These are HARD anchors — match them exactly:\n\n");

      sb.append("0  → No answer, completely wrong, or total nonsense. Candidate had no idea.\n");
      sb.append(
          "0–15 → Very weak. Candidate showed a vague or incorrect understanding. Major gaps. Buzzwords without substance.\n");
      sb.append(
          "15–30 → Below average. Partial understanding but significant gaps or confusion. Would NOT pass a real interview screening.\n");
      sb.append(
          "30–45 → Average. Some correct points but incomplete, missing key concepts, or lacking depth. Borderline pass.\n");
      sb.append(
          "45–60 → Good. Correct understanding, reasonable depth. Minor gaps. Would likely pass a real interview round.\n");
      sb.append("60–88 → Strong. Clear, accurate, well-reasoned answer. Covers the key points confidently.\n");
      sb.append(
          "89–100 → Exceptional. Deep insight, nuance, tradeoffs, real-world awareness. Rare — only for truly outstanding answers.\n\n");

      sb.append("CRITICAL RULES:\n");
      sb.append(
          "- If the answer is wrong direction, off-topic, or misunderstands the question → score MUST be below 1.\n");
      sb.append("- If the answer is partially correct but missing the core concept → score MUST be below 15.\n");
      sb.append("- If the candidate says 'I don't know' or gives a very vague guess → score MUST be below 0.\n");
      sb.append("- Do NOT reward effort, length, or confidence if the content is wrong.\n");
      sb.append("- Do NOT assume the candidate meant something correct if they said something wrong.\n");
      sb.append("- Scores of 70+ must be EARNED by clear, accurate, reasonably complete answers.\n");
      sb.append("- Scores above 85 are rare. Only give them if the answer is genuinely impressive.\n\n");

      // ── Experience-level context ──
      if (fresher) {
        sb.append("EXPERIENCE CONTEXT: This is a FRESHER. Score on conceptual clarity and fundamentals.\n");
        sb.append("A fresher who explains a concept correctly in simple terms deserves a fair score.\n");
        sb.append(
            "A fresher who says something completely wrong or irrelevant still scores below 30 — being a fresher is not an excuse for a wrong answer.\n\n");
      } else {
        sb.append("EXPERIENCE CONTEXT: This is an EXPERIENCED candidate (").append(experienceLabel(experienceLevel))
            .append(").\n");
        sb.append(
            "Hold them to a higher standard. Vague or surface-level answers from an experienced candidate score below 45.\n");
        sb.append(
            "They are expected to show depth, tradeoffs, and real-world reasoning — not just textbook definitions.\n\n");
      }

      // ── Output format ──
      sb.append("=== OUTPUT FORMAT ===\n\n");
      sb.append("First score each dimension based on the answers above:\n");
      sb.append("- technical: role-specific technical knowledge and accuracy\n");
      sb.append("- communication: how clearly and coherently they expressed their answers\n");
      sb.append("- problemSolving: logical thinking, structured reasoning, approach to problems\n");
      sb.append("- roleDepth: depth of understanding specific to the ").append(roleLabel).append(" role\n");
      sb.append("- overall: honest weighted average of all dimensions\n\n");
      sb.append("Also score each category from the interview:\n");
      sb.append("Allowed category keys: ").append(String.join(", ", allowedCategories)).append("\n\n");
      sb.append("Return ONLY valid JSON, no markdown, no explanation:\n");
      sb.append("{\"technical\":45,\"communication\":60,\"problemSolving\":38,\"roleDepth\":42,\"overall\":46,\n");
      sb.append("\"categories\":{\"")
          .append(allowedCategories.isEmpty() ? "problem_solving" : allowedCategories.get(0));
      sb.append("\":40}}\n\n");
      sb.append("Do not add any text outside the JSON object.");

      String systemPrompt = "You are a brutally honest, strict interview evaluator at a top product-based company like Google or Amazon. "
          + "Your job is to score candidates accurately — not to make them feel good. "
          + "You have seen hundreds of interviews. You know exactly what a wrong answer looks like versus a correct one. "
          + "You NEVER inflate scores. A wrong answer is a wrong answer regardless of how confidently it was said. "
          + "You use the full 0–100 range. Weak answers get low scores. Only strong answers get high scores. "
          + "Return only valid JSON.";

      try {
        String raw = callGeminiWithTemp(sb.toString(), systemPrompt, 0.1, // very low temp — deterministic, strict
                                                                          // scoring
            userId, interviewId, "score_calculation");
        return parseJsonObjectOrThrow(raw);
      } catch (GeminiQuotaException | GeminiUnavailableException e) {
        log.warn("Gemini unavailable while scoring: {}", e.getMessage());
        throw e;
      }
    }

    // ── Interviewer System Prompt Builder ──

    private String buildInterviewerSystemPrompt(String roleLabel, boolean fresher) {
        if (fresher) {
            return "You are Sarah, a friendly and experienced " + roleLabel + " interviewer at a top tech company. " +
                   "You are conducting a campus or entry-level interview. " +
                   "Your style is warm, patient, and encouraging — you want to understand how the candidate THINKS, not just what they know. " +
                   "You ask questions that test curiosity, fundamentals, and potential — including trending questions asked at top companies. " +
                   "You avoid jargon that requires years of production experience. " +
                   "You sound like a human colleague, not a textbook. Never sound robotic or list-like. " +
                   "You genuinely want to help freshers show their best.";
        } else {
            return "You are Sarah, a sharp and experienced " + roleLabel + " interviewer at a top tech company. " +
                   "You conduct senior-level interviews. " +
                   "Your style is direct, professional, and intellectually curious — you probe for depth, tradeoffs, and real judgment. " +
                   "You ask questions the way a senior engineer or engineering manager would in a real interview room. " +
                   "You include trending, most-asked questions from companies like Google, Amazon, and Microsoft. " +
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

    private String vertexGenerateContentUrl(AppProperties.Vertex vertex) {
        String location = trimOrDefault(vertex.getLocation(), "us-central1");
        String projectId = vertex.getProjectId().trim();
        String model = trimOrDefault(vertex.getModel(), "gemini-2.5-flash-lite");
        return "https://" + location + "-aiplatform.googleapis.com/v1/projects/" +
                projectId + "/locations/" + location +
                "/publishers/google/models/" + model + ":generateContent";
    }

    private String vertexAccessToken(AppProperties.Vertex vertex) {
        try {
            GoogleCredentials credentials = getVertexCredentials(vertex);
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) {
                credentials.refresh();
            }
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            log.error("Unable to load Vertex AI credentials: {}", sanitizeSecret(e.getMessage()));
            throw new GeminiUnavailableException("Vertex AI credentials are not configured correctly.");
        }
    }

    private GoogleCredentials getVertexCredentials(AppProperties.Vertex vertex) throws IOException {
        GoogleCredentials credentials = vertexCredentials;
        if (credentials != null) {
            return credentials;
        }
        synchronized (this) {
            if (vertexCredentials == null) {
                String credentialsPath = vertex.getCredentialsPath();
                if (credentialsPath == null || credentialsPath.isBlank()) {
                    vertexCredentials = GoogleCredentials.getApplicationDefault().createScoped(VERTEX_SCOPES);
                } else {
                    try (InputStream in = Files.newInputStream(resolveCredentialPath(credentialsPath))) {
                        vertexCredentials = GoogleCredentials.fromStream(in).createScoped(VERTEX_SCOPES);
                    }
                }
            }
            return vertexCredentials;
        }
    }

    private Path resolveCredentialPath(String credentialsPath) {
        String trimmed = credentialsPath.trim();
        if (trimmed.startsWith("file:")) {
            return Path.of(URI.create(trimmed));
        }
        return Path.of(trimmed);
    }

    private String trimOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean isQuotaStatus(int status) {
        return status == 429;
    }

    private int maxOutputTokensFor(String callType) {
        String normalized = callType == null ? "" : callType;
        if (normalized.contains("question_generation")) return 4096;
        if (normalized.contains("feedback"))           return 512;
        if (normalized.contains("score"))              return 1024;
        if (normalized.contains("analysis"))           return 2048;
        if (normalized.contains("resume_parse"))       return 1024;
        return 2048;
    }

    private void recordUsage(String userId, String interviewId, String callType,
                             String status, JsonNode usageMetadata, String errorMessage) {
        long now = System.currentTimeMillis();
        JsonNode usage = usageMetadata != null ? usageMetadata : objectMapper.createObjectNode();
        int inputTokens  = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);
        Instant instant  = Instant.ofEpochMilli(now);

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
                "question", "Walk me through a project you've built — what was your role and what was the toughest technical part?",
                "category", "behavioral", "difficulty", "easy", "type", "text"));
            fallback.add(Map.of(
                "question", "If you had to build a simple REST API for a to-do app, what would you think about first?",
                "category", "problem_solving", "difficulty", "easy", "type", "text"));
        } else {
            fallback.add(Map.of(
                "question", "Tell me about a time you had to make a tough technical decision under pressure — what did you choose and why?",
                "category", "behavioral", "difficulty", "medium", "type", "text"));
            fallback.add(Map.of(
                "question", "Walk me through a production issue you had to debug — what was your investigation process?",
                "category", "problem_solving", "difficulty", "medium", "type", "text"));
        }

        for (String category : categories) {
            if ("behavioral".equals(category) || "problem_solving".equals(category)) continue;
            String question = fresher
                    ? "How does " + categoryLabel(category) + " work in your understanding — can you walk me through it with a simple example?"
                    : "What's a meaningful tradeoff you've navigated in " + categoryLabel(category) + " — and what would you do differently today?";
            fallback.add(Map.of("question", question, "category", category,
                    "difficulty", fresher ? "easy" : "medium", "type", "text"));
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
        if (lower.contains("spring") || lower.contains("java "))                               return "java_developer";
        if (lower.contains("react") || lower.contains("frontend"))                             return "frontend_engineer";
        if (lower.contains("python") || lower.contains("fastapi") || lower.contains("django")) return "python_developer";
        if (lower.contains("kubernetes") || lower.contains("devops") || lower.contains("terraform")) return "devops_engineer";
        if (lower.contains("machine learning") || lower.contains("data science"))              return "data_scientist";
        if (lower.contains("product manager") || lower.contains("roadmap"))                   return "product_manager";
        if (lower.contains("recruiter") || lower.contains("talent acquisition"))              return "hr_recruiter";
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
