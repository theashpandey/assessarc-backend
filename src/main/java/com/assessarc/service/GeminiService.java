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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final AppProperties props;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GeminiUsageRepository geminiUsageRepository;
    private volatile GoogleCredentials vertexCredentials;
    private final QuestionGenerationRules questionGenerationRules;
    private static final String DEFAULT_ROLE = "software_engineer";
    private static final List<String> VERTEX_SCOPES = List.of("https://www.googleapis.com/auth/cloud-platform");
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(USAGE_ZONE);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM").withZone(USAGE_ZONE);

    private static final List<String> COMMON_CATEGORIES = List.of("problem_solving", "behavioral");
    private static final Set<String> FRESHER_LEVELS = Set.of("fresher", "1_3");

    private static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry("software_engineer", "Software Engineer"),
            Map.entry("ai_engineer", "AI Engineer"),
            Map.entry("generative_ai_engineer", "Generative AI Engineer"),
            Map.entry("machine_learning_engineer", "Machine Learning Engineer"),
            Map.entry("java_developer", "Java Developer"),
            Map.entry("python_developer", "Python Developer"),
            Map.entry("dotnet_developer", ".NET Developer"),
            Map.entry("csharp_developer", "C# Developer"),
            Map.entry("nodejs_developer", "Node.js Developer"),
            Map.entry("react_developer", "React Developer"),
            Map.entry("angular_developer", "Angular Developer"),
            Map.entry("full_stack_developer", "Full Stack Developer"),
            Map.entry("backend_engineer", "Backend Engineer"),
            Map.entry("frontend_engineer", "Frontend Engineer"),
            Map.entry("data_scientist", "Data Scientist"),
            Map.entry("data_analyst", "Data Analyst"),
            Map.entry("data_engineer", "Data Engineer"),
            Map.entry("sql_developer", "SQL Developer"),
            Map.entry("business_analyst", "Business Analyst"),
            Map.entry("devops_engineer", "DevOps Engineer"),
            Map.entry("cloud_engineer", "Cloud Engineer"),
            Map.entry("aws_engineer", "AWS Engineer"),
            Map.entry("azure_engineer", "Azure Engineer"),
            Map.entry("cybersecurity_analyst", "Cybersecurity Analyst"),
            Map.entry("qa_automation_engineer", "QA Automation Engineer"),
            Map.entry("sdet", "SDET"),
            Map.entry("mobile_developer", "Mobile Developer"),
            Map.entry("software_architect", "Software Architect"),
            Map.entry("engineering_manager", "Engineering Manager"),
            Map.entry("product_manager", "Product Manager"),
            Map.entry("prompt_engineer", "Prompt Engineer"),
            Map.entry("ui_ux_designer", "UI/UX Designer"),
            Map.entry("hr_recruiter", "HR / Recruiter")
    );

    private static final Map<String, String> ROLE_ALIASES = Map.ofEntries(
            Map.entry("net_developer", "dotnet_developer"),
            Map.entry("dot_net_developer", "dotnet_developer"),
            Map.entry("c_sharp_developer", "csharp_developer"),
            Map.entry("c_developer", "csharp_developer"),
            Map.entry("node_developer", "nodejs_developer"),
            Map.entry("node_js_developer", "nodejs_developer"),
            Map.entry("uiux_designer", "ui_ux_designer"),
            Map.entry("ux_ui_designer", "ui_ux_designer"),
            Map.entry("security_analyst", "cybersecurity_analyst"),
            Map.entry("cyber_security_analyst", "cybersecurity_analyst"),
            Map.entry("ml_engineer", "machine_learning_engineer"),
            Map.entry("gen_ai_engineer", "generative_ai_engineer")
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
            Map.entry("ai_engineer",            List.of("python_core", "machine_learning", "ai_ml_systems", "llm_applications", "model_evaluation", "mlops")),
            Map.entry("generative_ai_engineer", List.of("python_core", "generative_ai", "llm_applications", "prompt_engineering", "rag", "model_evaluation")),
            Map.entry("machine_learning_engineer", List.of("python_core", "machine_learning", "model_evaluation", "mlops", "data_modeling", "system_design")),
            Map.entry("java_developer",         List.of("java_core", "oops", "multithreading", "spring", "microservices", "system_design")),
            Map.entry("python_developer",       List.of("python_core", "oops", "django_fastapi", "api_design", "databases", "testing")),
            Map.entry("dotnet_developer",       List.of("dotnet", "csharp", "api_design", "databases", "system_design", "testing")),
            Map.entry("csharp_developer",       List.of("csharp", "dotnet", "oops", "api_design", "databases", "testing")),
            Map.entry("nodejs_developer",       List.of("nodejs", "javascript", "api_design", "databases", "async_programming", "testing")),
            Map.entry("react_developer",        List.of("javascript", "react", "frontend_architecture", "testing", "api_design", "performance")),
            Map.entry("angular_developer",      List.of("javascript", "angular", "frontend_architecture", "rxjs", "testing", "performance")),
            Map.entry("full_stack_developer",   List.of("javascript", "react", "api_design", "databases", "system_design", "cloud_devops")),
            Map.entry("backend_engineer",       List.of("api_design", "databases", "microservices", "system_design", "testing", "cloud_devops")),
            Map.entry("frontend_engineer",      List.of("javascript", "react", "frontend_architecture", "testing", "performance", "accessibility")),
            Map.entry("data_scientist",         List.of("python_core", "statistics", "machine_learning", "sql", "data_modeling", "experimentation")),
            Map.entry("data_analyst",           List.of("sql", "statistics", "analytics", "business_intelligence", "data_visualization", "experimentation")),
            Map.entry("data_engineer",          List.of("python_core", "sql", "data_modeling", "distributed_systems", "cloud_devops", "data_quality")),
            Map.entry("sql_developer",          List.of("sql", "databases", "query_optimization", "stored_procedures", "data_modeling", "performance")),
            Map.entry("business_analyst",       List.of("requirements_analysis", "business_intelligence", "process_modeling", "stakeholder_management", "metrics", "communication")),
            Map.entry("devops_engineer",        List.of("linux", "ci_cd", "cloud_devops", "kubernetes", "observability", "security")),
            Map.entry("cloud_engineer",         List.of("cloud_devops", "system_design", "networking", "security", "kubernetes", "cost_optimization")),
            Map.entry("aws_engineer",           List.of("aws", "networking", "iam", "cloud_security", "kubernetes", "cost_optimization")),
            Map.entry("azure_engineer",         List.of("azure", "networking", "iam", "cloud_security", "kubernetes", "cost_optimization")),
            Map.entry("cybersecurity_analyst",  List.of("security", "threat_detection", "incident_response", "vulnerability_management", "networking", "cloud_security")),
            Map.entry("qa_automation_engineer", List.of("testing", "automation_frameworks", "api_testing", "ci_cd", "debugging", "quality_strategy")),
            Map.entry("sdet",                   List.of("programming", "testing", "automation_frameworks", "api_testing", "ci_cd", "quality_strategy")),
            Map.entry("mobile_developer",       List.of("mobile_architecture", "ui_state", "api_design", "testing", "performance", "release_management")),
            Map.entry("software_architect",     List.of("architecture", "system_design", "microservices", "cloud_devops", "security", "leadership")),
            Map.entry("engineering_manager",    List.of("people_management", "leadership", "delivery", "hiring", "stakeholder_management", "technical_judgment")),
            Map.entry("product_manager",        List.of("product_strategy", "prioritization", "stakeholder_management", "metrics", "execution", "user_research")),
            Map.entry("prompt_engineer",        List.of("prompt_engineering", "llm_applications", "model_evaluation", "generative_ai", "user_research", "communication")),
            Map.entry("ui_ux_designer",         List.of("ux_research", "interaction_design", "design_systems", "prototyping", "accessibility", "user_research")),
            Map.entry("hr_recruiter",           List.of("hiring", "sourcing", "employee_relations", "communication", "process_management", "stakeholder_management"))
    );

    public record ResumeInsights(String summary, List<String> categories) {}

    // ── Role / Experience Helpers ──

    public String normalizeRole(String role) {
        if (role == null || role.isBlank()) return DEFAULT_ROLE;
        String normalized = normalizeRoleKey(role);
        return ROLE_CATEGORIES.containsKey(normalized) ? normalized : DEFAULT_ROLE;
    }

    public boolean isSupportedRole(String role) {
        if (role == null || role.isBlank()) return false;
        String normalized = normalizeRoleKey(role);
        return ROLE_CATEGORIES.containsKey(normalized);
    }

    private String normalizeRoleKey(String role) {
        String normalized = role.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return ROLE_ALIASES.getOrDefault(normalized, normalized);
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
      return Set.of(
          "java_developer",
          "python_developer",
          "react_developer",
          "full_stack_developer",
          "backend_engineer",
          "frontend_engineer",
          "software_engineer",
          "ai_engineer",
          "generative_ai_engineer",
          "machine_learning_engineer",
          "dotnet_developer",
          "csharp_developer",
          "nodejs_developer",
          "angular_developer",
          "data_scientist",
          "data_analyst",
          "data_engineer",
          "sql_developer",
          "qa_automation_engineer",
          "sdet",
          "mobile_developer"
      ).contains(normalized);
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
    public String transcribeAnswerAudio(byte[] audioBytes,
        String mimeType,
        String question,
        String interviewRole,
        String experienceLevel,
        String userId,
        String interviewId) {

    if (audioBytes == null || audioBytes.length == 0) {
        return "";
    }

    boolean usageRecorded = false;
    String callType = "audio_transcription";

    try {
        AppProperties.Vertex vertex = props.getGemini().getVertex();
        if (vertex == null || !vertex.isEnabled()) {
            throw new GeminiUnavailableException("Vertex AI service is not enabled");
        }
        if (vertex.getProjectId() == null || vertex.getProjectId().isBlank()) {
            throw new GeminiUnavailableException("Vertex AI project id is not configured");
        }

        String safeMime = mimeType == null || mimeType.isBlank() ? "audio/webm" : mimeType;

        // NO question/role/context in prompt — prevents hallucination when audio is silent
        String prompt = """
                You are a strict audio transcription engine. Your ONLY job is converting speech to text.

                Rules:
                - Transcribe ONLY the words actually spoken in the audio.
                - If the audio contains silence, background noise, music, or no human speech, output exactly: [EMPTY]
                - Never return like this: e.g. I'm sorry, but I cannot provide a transcript for the audio you've provided. It appears to contain only background noise and no discernible human speech. Therefore, according to the rules, I must output: [EMPTY]
                - Do NOT generate, infer, complete, summarize, or answer anything.
                - Do NOT use your own knowledge of any topic under any circumstance.
                - Preserve filler words, repetitions, incomplete sentences, and code-switching exactly as spoken.
                - If a word is unclear, write the most phonetically likely word — do not guess meaning.
                - Output only the raw transcript text. No labels, no markdown, no explanation.
                """;

        var parts = new ArrayList<Map<String, Object>>();
        parts.add(Map.of("text", prompt));
        parts.add(Map.of("inlineData", Map.of(
                "mimeType", safeMime,
                "data", Base64.getEncoder().encodeToString(audioBytes)
        )));

        var body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", Map.of(
                        "maxOutputTokens", maxOutputTokensFor(callType),
                        "temperature", 0.0,
                        "topP", 1.0,
                        "topK", 1        // most deterministic — least likely to hallucinate
                )
        );

        String responseStr = webClientBuilder.build()
                .post().uri(vertexGenerateContentUrl(vertex))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + vertexAccessToken(vertex))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .block();

        var json = objectMapper.readTree(responseStr);
        if (json.has("error")) {
            String errMsg = json.get("error").get("message").asText();
            log.error("Gemini audio transcription error: {}", sanitizeSecret(errMsg));
            recordUsage(userId, interviewId, callType, "ERROR", json.path("usageMetadata"), errMsg);
            usageRecorded = true;
            if (isQuotaMessage(errMsg)) {
                throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
            }
            throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
        }

        var candidates = json.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new GeminiUnavailableException("AI service returned no transcription.");
        }

        var text = new StringBuilder();
        for (var part : candidates.get(0).path("content").path("parts")) {
            if (part.has("text")) text.append(part.get("text").asText());
        }

        recordUsage(userId, interviewId, callType, "SUCCESS", json.path("usageMetadata"), null);
        usageRecorded = true;

        String cleaned = stripAudioTranscriptionArtifacts(text.toString());
        return guardHallucinatedAnswer(cleaned, question);

    } catch (WebClientResponseException e) {
        recordUsage(userId, interviewId, callType, "ERROR", null,
                "HTTP " + e.getStatusCode().value() + ": " +
                        truncate(sanitizeSecret(e.getResponseBodyAsString()), 180));
        if (isQuotaStatus(e.getStatusCode().value()) || isQuotaMessage(e.getResponseBodyAsString())) {
            throw new GeminiQuotaException("AI quota is temporarily exhausted. Please try again later.");
        }
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
        log.error("Gemini audio transcription failed: {}", sanitizeSecret(e.getMessage()));
        recordUsage(userId, interviewId, callType, "ERROR", null, sanitizeSecret(e.getMessage()));
        throw new GeminiUnavailableException("AI service is temporarily unavailable. Please try again.");
    }
}

private String stripAudioTranscriptionArtifacts(String value) {
    if (value == null) return "";
    String text = value
            .replaceAll("(?i)^\\s*(transcript|candidate|answer|spoken words)\\s*:\\s*", "")
            .replaceAll("```[a-zA-Z]*", "")
            .replace("```", "")
            .trim();

    // Unwrap surrounding quotes if present
    if ((text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("'") && text.endsWith("'"))) {
        text = text.substring(1, text.length() - 1).trim();
    }

    // Sentinel value returned by model when no speech detected
    if (text.equals("[EMPTY]")
            || text.equalsIgnoreCase("empty string")
            || text.equalsIgnoreCase("(empty)")
            || text.equalsIgnoreCase("no speech")
            || text.contains("[EMPTY]")
            || text.contains("empty")
            || text.equalsIgnoreCase("no candidate speech")
            || text.equalsIgnoreCase("no answer")) {
        return "";
    }

    return text;
}

/**
 * Last-resort guard: if the transcript looks like a hallucinated answer
 * (very long + shares too many keywords with the question), discard it.
 * This should rarely fire after the prompt fix, but acts as a safety net.
 */
private String guardHallucinatedAnswer(String transcript, String question) {
    if (transcript == null || transcript.isBlank()) return "";
    if (question == null || question.isBlank()) return transcript;

    // Short transcripts are almost certainly real speech
    if (transcript.length() <= 300) return transcript;

    String lowerTranscript = transcript.toLowerCase();
    long matchCount = Arrays.stream(question.toLowerCase().split("\\W+"))
            .filter(w -> w.length() > 5)
            .filter(lowerTranscript::contains)
            .count();

    // Heuristic: >5 distinct long keywords from the question found in a long
    // transcript → almost certainly a hallucinated answer, not real speech
    if (matchCount > 5) {
        log.warn("Discarding likely hallucinated transcription: length={}, keywordMatches={}",
                transcript.length(), matchCount);
        return "";
    }

    return transcript;
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
        String conceptDrills = questionGenerationRules.getConceptDrillExamples(normalizedRole, fresher);

        // Determine coding questions
        boolean hasCoding = roleRequiresCoding(role);
        int codingCount = 0;
        String codingInstructions = "";
        if (hasCoding) {
            if (durationMinutes == 30) {
                codingCount = 1;
           } else if (durationMinutes == 60) {
                codingCount = 2;
             }
            codingInstructions =  questionGenerationRules.getCodingInstructions(durationMinutes);
        }
        int textCount = count - codingCount;

        // ── Build bucket counts ──
        int fundamentalsCount;
        int trickyCount;
        int scenarioCount;
        int projectCount;
        int behavioralCount;

        if (fresher) {
            fundamentalsCount = Math.round(textCount * 0.50f);
            trickyCount       = Math.round(textCount * 0.25f);
            scenarioCount     = Math.round(textCount * 0.10f);
            projectCount      = Math.round(textCount * 0.10f);
            behavioralCount   = textCount - fundamentalsCount - trickyCount - scenarioCount - projectCount;
        } else {
            fundamentalsCount = Math.round(textCount * 0.30f);
            trickyCount       = Math.round(textCount * 0.20f);
            scenarioCount     = Math.round(textCount * 0.20f);
            projectCount      = Math.round(textCount * 0.20f);
            behavioralCount   = textCount - fundamentalsCount - trickyCount - scenarioCount - projectCount;
        }
        if (textCount > 0 && behavioralCount < 1) {
            if (projectCount > 1) projectCount--;
            else if (scenarioCount > 1) scenarioCount--;
            else if (trickyCount > 1) trickyCount--;
            else fundamentalsCount = Math.max(0, fundamentalsCount - 1);
            behavioralCount = 1;
        }

        String depthInstructions = questionGenerationRules.buildDepthInstructions(normalizedRole, fresher, expLabel,
                textCount, fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount);
        boolean initialGeneration = callType == null || callType.contains("initial");
        String flowInstructions = initialGeneration
                ? "REAL INTERVIEW FLOW RULES:\n"
                + "- Do NOT generate any introduction, warm-up, background, or 'tell me about yourself' question.\n"
                + "- Generate only technical/conceptual/tricky/scenario/project/behavioral questions that come after the backend intro question.\n"
                : "REAL INTERVIEW CONTINUATION RULES:\n"
                + "- Continue from the questions already asked. Do NOT ask any introduction, warm-up, background, or 'tell me about yourself' question.\n"
                + "- Keep the remaining questions varied across fundamentals, concept understanding, tricky/gotcha checks, scenarios, resume/project depth, and behavioral judgment.\n";
        flowInstructions += "- Do not over-personalize every question from the resume. Resume/project questions are only one part of the interview.\n"
                + "- For freshers, prioritize college-placement style fundamentals, concept clarity, simple tricky questions, beginner coding/problem-solving, and project explanation.\n"
                + "- For freshers, include direct bookish/conceptual questions like definitions, differences, annotations, lifecycle, joins, indexes, testing terms, cloud basics, or role-specific vocabulary.\n"
                + "- For freshers, avoid production ownership language unless their resume clearly shows real work experience.\n\n";

        String prompt = String.format(
                "Target interview role: %s\n" +
                "Experience level: %s\n\n" +
                "Candidate profile from resume (use this to personalize questions — reference actual skills, tools, and projects from the resume where relevant):\n%s\n\n" +
                "Already asked questions (DO NOT repeat or ask similar ones):\n- %s\n\n" +
                "%s\n\n" +
                "Role-specific conceptual/bookish question examples (DO NOT copy verbatim every time; use this to understand the expected style):\n%s\n\n" +
                "Generate exactly %d TEXT questions and %d CODING questions.\n" +
                "Allowed categories: %s\n\n" +
                "%s\n\n" +
                "%s" +
                "IMPORTANT — QUESTION QUALITY RULES:\n" +
                "- Include 40 percent trending, most-asked questions from top tech companies Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc. relevant to this role and experience level.\n" +
                "- Each question must sound like a real human interviewer saying it out loud — natural, conversational, not robotic.\n" +
                "- Mix question types: fundamentals, tricky/gotcha, scenario-based, resume/project-based, and behavioral — as per the bucket distribution above.\n" +
                "- Standalone conceptual questions are REQUIRED. Do not convert every concept into a scenario or resume/project question.\n" +
                "- Use real interview concept checks such as 'What is X?', 'What is the difference between X and Y?', 'How does X work?', and 'When would X fail?'\n" +
                "- For freshers, conceptual + tricky conceptual questions must dominate the TEXT question set.\n" +
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
                "- Every Test example must be consistent with the problem description\n" +
                "- Put the full statement only in codingData.description.\n" +
                "- Put every sample input/output only in codingData.testCases.\n" +
                "- Put the return/output requirement only in codingData.expectedOutput.\n\n" +
                "Rules:\n" +
                "- category must be one of: %s\n" +
                "- difficulty: easy | medium | hard (NEVER hard for coding)\n" +
                "- type: text | coding",
                roleLabel, expLabel, resumeSummary, existing,
                depthInstructions,
                conceptDrills,
                textCount, codingCount, allowed, codingInstructions,
                flowInstructions,
                codingLanguageForRole(normalizedRole),
                allowed
        );

        String systemPrompt = questionGenerationRules.buildInterviewerSystemPrompt(roleLabel, fresher);

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

  
    // ── Coding Language Helper ──

    private String codingLanguageForRole(String normalizedRole) {
        return switch (normalizedRole) {
            case "java_developer", "backend_engineer", "software_architect"  -> "java";
            case "python_developer", "data_scientist", "data_engineer",
                 "ai_engineer", "generative_ai_engineer",
                 "machine_learning_engineer"                                 -> "python";
            case "data_analyst", "sql_developer"                             -> "sql";
            case "dotnet_developer", "csharp_developer"                      -> "csharp";
            case "react_developer", "frontend_engineer", "nodejs_developer",
                 "angular_developer", "full_stack_developer"                 -> "javascript";
            case "qa_automation_engineer", "sdet"                            -> "java";
            case "mobile_developer"                                           -> "kotlin";
            default                                                          -> "";
        };
    }

    // ── Feedback Generation ──
public String correctTranscript(String transcript, String userId, String interviewId) {
    String raw = transcript == null ? "" : transcript.trim();
    if (raw.isBlank()) return "";

    String systemPrompt = """
            You are a spelling-only corrector for speech-to-text transcripts from technical interviews.

            YOUR ONLY JOB:
            * Fix words that were misheared or misspelled by the speech-to-text engine.
            * Correct technical terms that sound similar but were transcribed incorrectly
              (e.g. "rabbit mq" → "RabbitMQ", "git hub" → "GitHub", "cue bernetti's" → "Kubernetes").
            * Fix obvious capitalization of proper nouns and technical names.

            STRICT RULES — NEVER VIOLATE:
            * Do NOT change word order.
            * Do NOT add or remove any words.
            * Do NOT restructure or rephrase any sentence.
            * Do NOT improve grammar or fluency.
            * Do NOT fix run-on sentences or fragments — leave them as-is.
            * Do NOT add punctuation beyond what is already implied.
            * Do NOT summarize or paraphrase anything.
            * Preserve filler words exactly: "um", "uh", "like", "you know", "so", "basically", etc.
            * Preserve repetitions and self-corrections exactly as spoken.
            * Preserve the user's natural speaking rhythm and flow.

            OUTPUT:
            * Return ONLY the corrected transcript text.
            * No explanations, no notes, no formatting, no markdown.

            Think of yourself as autocorrect — not an editor.
            You fix typos. You do not rewrite.

            Common Technical Terms Reference (for spelling correction only):
            Java, Spring Boot, Hibernate, Kafka, RabbitMQ, Redis, PostgreSQL, MySQL, MongoDB,
            Cosmos DB, Azure Service Bus, ActiveMQ, IBM MQ, Kubernetes, Docker, Microservices,
            REST API, Snowflake, Splunk, NetSuite, Salesforce, SAP, Workday, SuccessFactors,
            BigQuery, Apache Camel, JPA, Maven, Gradle, Jenkins, GitHub Actions, SonarQube,
            JUnit, Mockito, React, TypeScript, Node.js, JWT, OAuth, Elasticsearch.
            """;

    // Few-shot examples baked into the user turn to anchor behavior
    String userPrompt = """
            Examples of what to do:

            INPUT:  "i used rabbit mq for a sync communication between the micro services"
            OUTPUT: "i used RabbitMQ for async communication between the microservices"

            INPUT:  "so basically um we had like a cue bernetti's cluster with like three nodes"
            OUTPUT: "so basically um we had like a Kubernetes cluster with like three nodes"

            INPUT:  "i implemented the restful a p i using spring boot and j p a"
            OUTPUT: "i implemented the RESTful API using Spring Boot and JPA"

            INPUT:  "we where using post gress and then migrated to mongo db"
            OUTPUT: "we were using PostgreSQL and then migrated to MongoDB"

            Now correct this transcript (spelling and technical terms only, no other changes):

            """ + raw;

    String corrected = callGeminiWithTemp(userPrompt, systemPrompt, 0.0, userId, interviewId, "transcript_correction");
    return stripTranscriptCorrectionArtifacts(corrected);
}

    private String stripTranscriptCorrectionArtifacts(String value) {
        if (value == null) return "";
        String text = value.replaceAll("(?i)^\\s*(corrected transcript|output)\\s*:\\s*", "")
                .replaceAll("```[a-zA-Z]*", "")
                .replace("```", "")
                .trim();
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

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

        String systemPrompt = "You are Sarah, a real human-style " + roleLabel + " interviewer. "
                + "Give short, natural, spoken feedback only. Be specific to the candidate's actual answer. "
                + "No markdown, no bullets, no long coaching essay.";
        return callGeminiWithTemp(prompt, systemPrompt, 0.35, userId, interviewId, "answer_feedback");
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

    // ── Separate answered vs unanswered categories ──
    Set<String> answeredCategories = qaList.stream()
            .filter(qa -> {
                String ans = qa.getOrDefault("answer", "");
                return ans != null && !ans.isBlank();
            })
            .map(qa -> qa.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    List<String> scorableCategories = allowedCategories.stream()
            .filter(answeredCategories::contains)
            .collect(Collectors.toList());

    List<String> unansweredCategories = allowedCategories.stream()
            .filter(c -> !answeredCategories.contains(c))
            .collect(Collectors.toList());

    // ── Edge case: user answered nothing ──
    if (scorableCategories.isEmpty()) {
        Map<String, Object> zeroScores = new HashMap<>();
        zeroScores.put("technical", 0);
        zeroScores.put("communication", 0);
        zeroScores.put("problemSolving", 0);
        zeroScores.put("roleDepth", 0);
        zeroScores.put("overall", 0);
        Map<String, Integer> zeroCats = new HashMap<>();
        allowedCategories.forEach(c -> zeroCats.put(c, 0));
        zeroScores.put("categories", zeroCats);
        return zeroScores;
    }

    var sb = new StringBuilder();

    // ── Per-answer block — only answered questions ──
    sb.append("You are evaluating a mock interview for a ").append(roleLabel).append(" candidate.\n");
    sb.append("Experience level: ").append(experienceLabel(experienceLevel)).append("\n\n");
    sb.append("Below are the interview questions and the candidate's actual answers.\n");
    sb.append("Read each answer carefully and judge it STRICTLY on its own merit.\n\n");
    sb.append("=== INTERVIEW RESPONSES ===\n\n");

    int qNum = 1;
    for (var qa : qaList) {
        String answer = qa.getOrDefault("answer", "");
        String answerText = (answer == null || answer.isBlank())
                ? "(no answer given — candidate was silent or skipped)"
                : answer.trim();
        String catLabel = categoryLabel(qa.get("category"));
        sb.append("Q").append(qNum).append(" [").append(catLabel).append("]: ")
                .append(qa.get("question")).append("\n");
        sb.append("Answer: ").append(answerText).append("\n\n");
        qNum++;
    }

    // ── Scoring rubric with hard anchors ──
    sb.append("=== SCORING RUBRIC (MANDATORY — READ BEFORE SCORING) ===\n\n");
    sb.append("Score range is 0–100. Use the FULL range. These are HARD anchors — match them exactly:\n\n");
    sb.append("0  → No answer, completely wrong, or total nonsense. Candidate had no idea.\n");
    sb.append("0–15 → Very weak. Candidate showed a vague or incorrect understanding. Major gaps. Buzzwords without substance.\n");
    sb.append("15–30 → Below average. Partial understanding but significant gaps or confusion. Would NOT pass a real interview screening.\n");
    sb.append("30–45 → Average. Some correct points but incomplete, missing key concepts, or lacking depth. Borderline pass.\n");
    sb.append("45–60 → Good. Correct understanding, reasonable depth. Minor gaps. Would likely pass a real interview round.\n");
    sb.append("60–88 → Strong. Clear, accurate, well-reasoned answer. Covers the key points confidently.\n");
    sb.append("89–100 → Exceptional. Deep insight, nuance, tradeoffs, real-world awareness. Rare — only for truly outstanding answers.\n\n");

    sb.append("CRITICAL RULES:\n");
    sb.append("- If the answer is wrong direction, off-topic, or misunderstands the question → score MUST be below 1.\n");
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
        sb.append("A fresher who says something completely wrong or irrelevant still scores below 30 — being a fresher is not an excuse for a wrong answer.\n\n");
    } else {
        sb.append("EXPERIENCE CONTEXT: This is an EXPERIENCED candidate (")
                .append(experienceLabel(experienceLevel)).append(").\n");
        sb.append("Hold them to a higher standard. Vague or surface-level answers from an experienced candidate score below 45.\n");
        sb.append("They are expected to show depth, tradeoffs, and real-world reasoning — not just textbook definitions.\n\n");
    }

    // ── Category scoring instructions ──
    sb.append("=== CATEGORY SCORING RULES ===\n\n");
    sb.append("Score ONLY these categories where the candidate actually gave answers:\n");
    sb.append("  Scorable: ").append(String.join(", ", scorableCategories)).append("\n\n");

    if (!unansweredCategories.isEmpty()) {
        sb.append("These categories were NOT attempted by the candidate — you MUST set them to 0 in your output:\n");
        sb.append("  Zero (not attempted): ").append(String.join(", ", unansweredCategories)).append("\n\n");
    }

    sb.append("IMPORTANT:\n");
    sb.append("- Do NOT invent or hallucinate scores for unanswered categories.\n");
    sb.append("- Do NOT add any category key that is not in the full list below.\n");
    sb.append("- Every category in the full list must appear in your JSON output.\n");
    sb.append("- Full category list (all must appear): ")
            .append(String.join(", ", allowedCategories)).append("\n\n");

    // ── Dimension + overall scoring ──
    sb.append("=== OUTPUT FORMAT ===\n\n");
    sb.append("Score each dimension based ONLY on answered questions:\n");
    sb.append("- technical: role-specific technical knowledge and accuracy\n");
    sb.append("- communication: how clearly and coherently they expressed their answers\n");
    sb.append("- problemSolving: logical thinking, structured reasoning, approach to problems\n");
    sb.append("- roleDepth: depth of understanding specific to the ").append(roleLabel).append(" role\n\n");

    sb.append("For 'overall': compute a weighted average across ALL categories in the full list.\n");
    sb.append("Unanswered categories count as 0 and MUST drag the overall score down proportionally.\n");
    sb.append("Example: if 1 of 4 categories was answered and scored 70, overall must be around 17–18, NOT 70.\n\n");

    sb.append("Return ONLY valid JSON, no markdown, no explanation:\n");
    sb.append("{\"technical\":45,\"communication\":60,\"problemSolving\":38,\"roleDepth\":42,\"overall\":18,\n");
    sb.append("\"categories\":{\"")
            .append(scorableCategories.get(0)).append("\":55");
    unansweredCategories.forEach(c -> sb.append(",\"").append(c).append("\":0"));
    sb.append("}}\n\n");
    sb.append("Do not add any text outside the JSON object.");

    String systemPrompt = "You are a brutally honest, strict interview evaluator at a top product-based company like Google or Amazon. "
            + "Your job is to score candidates accurately — not to make them feel good. "
            + "You have seen hundreds of interviews. You know exactly what a wrong answer looks like versus a correct one. "
            + "You NEVER inflate scores. A wrong answer is a wrong answer regardless of how confidently it was said. "
            + "Unanswered categories are 0 — you never invent scores for questions the candidate did not attempt. "
            + "The overall score must reflect the full interview, not just the attempted portion. "
            + "Return only valid JSON.";

    try {
        String raw = callGeminiWithTemp(sb.toString(), systemPrompt, 0.1,
                userId, interviewId, "score_calculation");

        Map<String, Object> result = parseJsonObjectOrThrow(raw);

        // ── Post-process safety net: force unanswered categories to 0 ──
        // Even if Gemini hallucinated a score, we enforce 0 here
        if (result.get("categories") instanceof Map<?, ?> cats) {
            @SuppressWarnings("unchecked")
            Map<String, Object> categoryScores = (Map<String, Object>) cats;
            unansweredCategories.forEach(c -> categoryScores.put(c, 0));

            // Recompute overall as true average across ALL categories
            if (!allowedCategories.isEmpty()) {
                double trueOverall = allowedCategories.stream()
                        .mapToDouble(c -> {
                            Object val = categoryScores.get(c);
                            if (val instanceof Number n) return n.doubleValue();
                            return 0.0;
                        })
                        .average()
                        .orElse(0.0);
                result.put("overall", (int) Math.round(trueOverall));
            }
        }

        return result;

    } catch (GeminiQuotaException | GeminiUnavailableException e) {
        log.warn("Gemini unavailable while scoring: {}", e.getMessage());
        throw e;
    }
}
    // ── Interviewer System Prompt Builder ──

  

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
        if (normalized.contains("question_generation")) return 3072;
        if (normalized.contains("transcript_correction")) return 512;
        if (normalized.contains("feedback"))           return 384;
        if (normalized.contains("audio_transcription")) return 2048;
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
                "question", "What is one core concept in this role that every fresher should know, and how would you explain it simply?",
                "category", firstNonCommonCategory(categories), "difficulty", "easy", "type", "text"));
            fallback.add(Map.of(
                "question", "What is the difference between two commonly confused basics in this role, and when would you use each one?",
                "category", firstNonCommonCategory(categories), "difficulty", "easy", "type", "text"));
            fallback.add(Map.of(
                "question", "Walk me through a project you've built - what was your role and what was the toughest technical part?",
                "category", "behavioral", "difficulty", "easy", "type", "text"));
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

    private String firstNonCommonCategory(List<String> categories) {
        if (categories == null || categories.isEmpty()) return "problem_solving";
        return categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .filter(category -> !"behavioral".equals(category) && !"problem_solving".equals(category))
                .findFirst()
                .orElse(categories.get(0));
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
        if (lower.contains("generative ai") || lower.contains("genai") || lower.contains("rag") || lower.contains("llm")) return "generative_ai_engineer";
        if (lower.contains("prompt engineer") || lower.contains("prompt engineering"))       return "prompt_engineer";
        if (lower.contains("machine learning engineer") || lower.contains("ml engineer"))     return "machine_learning_engineer";
        if (lower.contains("ai engineer"))                                                    return "ai_engineer";
        if (lower.contains(".net") || lower.contains("asp.net"))                              return "dotnet_developer";
        if (lower.contains("c#") || lower.contains("c sharp"))                                return "csharp_developer";
        if (lower.contains("node.js") || lower.contains("nodejs") || lower.contains("express")) return "nodejs_developer";
        if (lower.contains("angular"))                                                        return "angular_developer";
        if (lower.contains("spring") || lower.contains("java "))                               return "java_developer";
        if (lower.contains("react") || lower.contains("frontend"))                             return "frontend_engineer";
        if (lower.contains("python") || lower.contains("fastapi") || lower.contains("django")) return "python_developer";
        if (lower.contains("data analyst") || lower.contains("power bi") || lower.contains("tableau")) return "data_analyst";
        if (lower.contains("sql developer") || lower.contains("stored procedure"))             return "sql_developer";
        if (lower.contains("business analyst") || lower.contains("requirements analysis"))     return "business_analyst";
        if (lower.contains("aws "))                                                            return "aws_engineer";
        if (lower.contains("azure"))                                                           return "azure_engineer";
        if (lower.contains("cybersecurity") || lower.contains("soc analyst") || lower.contains("security analyst")) return "cybersecurity_analyst";
        if (lower.contains("sdet"))                                                            return "sdet";
        if (lower.contains("kubernetes") || lower.contains("devops") || lower.contains("terraform")) return "devops_engineer";
        if (lower.contains("machine learning") || lower.contains("data science"))              return "data_scientist";
        if (lower.contains("ui/ux") || lower.contains("ux designer") || lower.contains("ui designer")) return "ui_ux_designer";
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
