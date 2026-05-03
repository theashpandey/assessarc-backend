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
                + "Java skills and frameworks, projects, job titles, tech stack. "
                + "Be concise, under 250 words. Focus on technical skills.";

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
                                                        int count) {
        String existing = existingTexts.isEmpty() ? "none" :
                String.join("\n- ", existingTexts);

        String prompt = String.format(
                "Java developer profile:\n%s\n\n" +
                "Already selected questions (DO NOT ask similar ones):\n- %s\n\n" +
                "Generate exactly %d NEW, unique Java interview questions. " +
                "Make them conversational, natural, NOT robotic. " +
                "Vary categories across: %s\n" +
                "Each question should sound like a real senior interviewer asking it.\n" +
                "Mix difficulty: some easy warm-ups, mostly medium, a few hard.\n\n" +
                "Return ONLY valid JSON array:\n" +
                "[{\"question\":\"...\",\"category\":\"java_core\",\"difficulty\":\"medium\"}, ...]\n" +
                "category must be one of: %s\n" +
                "difficulty: easy | medium | hard",
                resumeSummary, existing, count,
                String.join(", ", CATEGORIES),
                String.join(", ", CATEGORIES)
        );

        try {
            String raw = callGeminiWithTemp(prompt,
                    "You are a senior Java technical interviewer at a top product company. " +
                    "Generate natural, varied interview questions. Return only valid JSON array.", 0.9);
            return safeParseJsonArray(raw);
        } catch (GeminiQuotaException e) {
            log.warn("Gemini quota exhausted while generating questions; using fallback questions");
            return fallbackQuestions(count);
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
            return fallbackQuestions(count);
        }
    }

    /**
     * Generate conversational feedback for a single answer.
     * Sounds like a human interviewer, not a bot.
     */
    public String generateFeedback(String question, String category,
                                    String answer, String prevQuestion, String prevAnswer) {
        String prevCtx = "";
        if (prevQuestion != null && !prevQuestion.isBlank() && prevAnswer != null && !prevAnswer.isBlank()) {
            prevCtx = "Previous question: \"" + prevQuestion + "\"\n" +
                      "Candidate answered: \"" + prevAnswer.substring(0, Math.min(120, prevAnswer.length())) + "...\"\n\n";
        }

        String catLabel = CAT_LABELS.getOrDefault(category, category);
        String answerSnippet = answer != null ? answer : "(no answer given)";

        String prompt = prevCtx +
                "Current question [" + catLabel + "]: \"" + question + "\"\n\n" +
                "Candidate's answer: \"" + answerSnippet + "\"\n\n" +
                "Give 2-3 sentence verbal feedback exactly as a human interviewer would say it live. " +
                "Start naturally — like 'Good answer!', 'That's a solid start,', 'Interesting approach,' etc. " +
                "Mention ONE thing they did well. Mention ONE specific thing to improve or add. " +
                "Sound warm but professional. No bullet points. No markdown. Conversational spoken style.";

        return callGemini(prompt,
                "You are Sarah, a friendly but professional Java technical interviewer at Google. " +
                "Give short, natural spoken feedback — the kind you'd hear in a real interview room. " +
                "Be specific, not generic. Sound human.");
    }

    /**
     * Score completed interview — returns scores map
     */
    public Map<String, Object> calculateScores(List<Map<String, String>> qaList) {
        var sb = new StringBuilder("Interview Q&As to evaluate:\n\n");
        for (var qa : qaList) {
            String catLabel = CAT_LABELS.getOrDefault(qa.get("category"), qa.get("category"));
            String answer = qa.getOrDefault("answer", "(no answer)");
            if (answer.isBlank()) answer = "(no answer given)";
            sb.append("[").append(catLabel).append("] Q: ").append(qa.get("question"))
              .append("\nA: ").append(answer).append("\n\n");
        }
        sb.append("Score each dimension out of 100 based on the actual answers. Be realistic and strict.\n")
          .append("Return ONLY valid JSON (no markdown):\n")
          .append("{\"technical\":75,\"communication\":80,\"problemSolving\":70,")
          .append("\"javaDepth\":78,\"overall\":76,")
          .append("\"categories\":{\"java_core\":72,\"oops\":80,\"multithreading\":65,")
          .append("\"spring\":70,\"system_design\":68,\"problem_solving\":72,\"behavioral\":85}}");

        try {
            String raw = callGeminiWithTemp(sb.toString(),
                    "You are a strict interview evaluator. Score realistically based on answer quality. " +
                    "Do not give inflated scores. Return only valid JSON.", 0.3);
            return safeParseJsonObject(raw);
        } catch (GeminiQuotaException e) {
            log.warn("Gemini quota exhausted while scoring; using fallback scores");
            return fallbackScores();
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

    private List<Map<String, String>> fallbackQuestions(int count) {
        List<Map<String, String>> fallback = List.of(
                Map.of("question", "Can you walk me through one Java project you worked on recently and explain your main responsibilities?", "category", "behavioral", "difficulty", "medium"),
                Map.of("question", "How would you explain the difference between an interface and an abstract class in Java, and when would you choose each?", "category", "oops", "difficulty", "medium"),
                Map.of("question", "What happens inside a Spring Boot application when it starts up?", "category", "spring", "difficulty", "medium"),
                Map.of("question", "How do you handle exceptions in a REST API so clients get useful error responses?", "category", "spring", "difficulty", "medium"),
                Map.of("question", "Can you explain how HashMap works internally in Java?", "category", "java_core", "difficulty", "medium"),
                Map.of("question", "How would you make a piece of Java code thread-safe?", "category", "multithreading", "difficulty", "medium"),
                Map.of("question", "Tell me about a time you had to debug a difficult production or testing issue.", "category", "behavioral", "difficulty", "medium"),
                Map.of("question", "How would you design a simple service for handling user registrations and login?", "category", "system_design", "difficulty", "medium"),
                Map.of("question", "What are the main differences between ArrayList and LinkedList?", "category", "java_core", "difficulty", "easy"),
                Map.of("question", "How do you approach breaking down a problem before writing code?", "category", "problem_solving", "difficulty", "medium")
        );
        return fallback.stream().limit(Math.max(1, Math.min(count, fallback.size()))).toList();
    }

    private Map<String, Object> fallbackScores() {
        return Map.of(
                "overall", 65,
                "technical", 65,
                "communication", 65,
                "problemSolving", 65,
                "javaDepth", 65,
                "categories", Map.of(
                        "java_core", 65,
                        "oops", 65,
                        "multithreading", 65,
                        "spring", 65,
                        "system_design", 65,
                        "problem_solving", 65,
                        "behavioral", 65
                )
        );
    }
}