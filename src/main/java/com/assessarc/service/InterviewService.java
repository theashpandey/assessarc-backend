package com.assessarc.service;

import com.assessarc.config.AppProperties;
import com.assessarc.dto.Dto;
import com.assessarc.dto.Dto.QADetailDto;
import com.assessarc.model.Interview;
import com.assessarc.model.User;
import com.assessarc.repository.InterviewRepository;
import com.assessarc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final int TARGET_QUESTION_COUNT = 10;
    private static final int HISTORICAL_INTERVIEW_LOOKBACK = 1;
    private static final String ANALYSIS_PENDING_MESSAGE =
            "Your answers are saved. The AI scoring service is temporarily unavailable, so your detailed report is pending. Please check Performance again later.";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final GeminiService geminiService;
    private final AppProperties props;

    // ── Start Interview ──
    public Dto.StartInterviewResponse startInterview(String uid, Dto.StartInterviewRequest req) {
        User user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        int durationMinutes = req.getDurationMinutes();
        String requestedRole = req.getInterviewRole() != null && !req.getInterviewRole().isBlank()
                ? req.getInterviewRole() : user.getInterviewRole();
        String requestedExperience = req.getExperienceLevel() != null && !req.getExperienceLevel().isBlank()
                ? req.getExperienceLevel() : user.getExperienceLevel();

        if (requestedRole == null || requestedRole.isBlank()) {
            throw new RuntimeException("Interview role is required. Please save your role before starting.");
        }
        if (requestedExperience == null || requestedExperience.isBlank()) {
            throw new RuntimeException("Experience level is required. Please save your experience level before starting.");
        }
        if (!geminiService.isSupportedRole(requestedRole)) {
            throw new RuntimeException("Unsupported interview role. Please save a valid role before starting.");
        }
        if (!geminiService.isSupportedExperience(requestedExperience)) {
            throw new RuntimeException("Unsupported experience level. Please save a valid experience level before starting.");
        }

        String interviewRole = geminiService.normalizeRole(requestedRole);
        String experienceLevel = geminiService.normalizeExperience(requestedExperience);

        // Validate duration
        if (durationMinutes != 30 && durationMinutes != 60) {
            throw new RuntimeException("Duration must be 30 or 60 minutes");
        }

        // Check resume
        if (user.getResumeText() == null || user.getResumeText().isBlank()) {
            throw new RuntimeException("Please upload your resume first.");
        }

        // Check wallet
        int price = durationMinutes == 30
                ? props.getWallet().getPrice30min()
                : props.getWallet().getPrice60min();
        int availableCredits = totalCredits(user);
        if (availableCredits < price) {
            throw new RuntimeException("Insufficient credits. Need " + price
                    + " credits, you have " + availableCredits + ".");
        }

        // Deduct wallet first (reserve credits)
        // Parse resume — use cached summary if exists to save Gemini calls
        String resumeSummary = user.getResumeSummary();
        List<String> resumeCategories = user.getResumeCategories();
        if (resumeSummary == null || resumeSummary.isBlank()
                || resumeCategories == null || resumeCategories.isEmpty()) {
            log.info("Parsing resume insights for user {}", uid);
            GeminiService.ResumeInsights insights = geminiService.parseResumeInsights(user.getResumeText(), uid, null);
            resumeSummary = insights.summary();
            resumeCategories = insights.categories();
            userRepository.updateResumeInsights(uid, resumeSummary, resumeCategories);
        }

        String interviewId = UUID.randomUUID().toString();

        // Build question list with smart dedup
        List<String> historicalQuestions = collectHistoricalQuestionTexts(uid, null);
        log.info("Building question set. User has {} historical questions.", historicalQuestions.size());

        List<Dto.QuestionDto> questions = buildQuestions(
                resumeSummary, historicalQuestions, interviewRole, experienceLevel, resumeCategories,
                durationMinutes, uid, interviewId, "initial_question_generation");
        if (questions.size() < 2) {
            throw new RuntimeException("Could not generate enough questions. Please try again.");
        }

        List<Interview.QuestionAnswer> generatedQuestions = questions.stream()
                .map(q -> Interview.QuestionAnswer.builder()
                        .questionId(q.getId())
                        .question(q.getQuestion())
                        .category(q.getCategory())
                        .difficulty(q.getDifficulty())
                        .type(q.getType())
                        .codingData(toInterviewCodingData(q.getCodingData()))
                        .answer("")
                        .feedback("")
                        .build())
                .collect(Collectors.toList());
        List<Interview.QuestionAnswer> askedQuestions = new ArrayList<>();
        askedQuestions.add(generatedQuestions.get(0));
        List<Interview.QuestionAnswer> pooledQuestions =
                new ArrayList<>(generatedQuestions.subList(1, generatedQuestions.size()));

        Interview interview = Interview.builder()
                .id(interviewId)
                .userId(uid)
                .status("STARTED")
                .durationMinutes(durationMinutes)
                .interviewRole(interviewRole)
                .experienceLevel(experienceLevel)
                .creditsDeducted(price)
                .startedAt(System.currentTimeMillis())
                .resumeSummary(resumeSummary)
                .questions(askedQuestions)
                .questionPool(pooledQuestions)
                .build();

        int newBalance = interviewRepository.saveStartedWithWalletDebit(interview, uid, price);
        log.info("Interview {} started for user {}", interview.getId(), uid);

        return Dto.StartInterviewResponse.builder()
                .interviewId(interview.getId())
                .resumeSummary(resumeSummary)
                .interviewRole(interviewRole)
                .experienceLevel(experienceLevel)
                .questions(List.of(questions.get(0)))
                .creditsDeducted(price)
                .walletBalance(newBalance)
                .build();
    }

    /**
     * Build question list:
     * - 50-60% from central question bank (avoiding questions user has seen before)
     * - Rest generated fresh by AI
     * - Shuffle so order is not predictable
     * - No duplicate questions within a session
     */
    private List<Dto.QuestionDto> buildQuestions(String resumeSummary, List<String> existingQuestionTexts,
                                                 String interviewRole, String experienceLevel,
                                                 List<String> resumeCategories, int durationMinutes,
                                                 String uid, String interviewId, String callType) {
        List<String> allowedCategories = geminiService.categoriesForInterview(interviewRole, resumeCategories);
        List<Dto.QuestionDto> result = new ArrayList<>();
        Set<String> seenQuestionTexts = existingQuestionTexts.stream()
                .map(this::normalizeQuestionText)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toCollection(HashSet::new));

        log.info("Generating {} fresh questions.", TARGET_QUESTION_COUNT);
        List<Map<String, Object>> newQs = geminiService.generateQuestions(
                resumeSummary, existingQuestionTexts, TARGET_QUESTION_COUNT,
                interviewRole, experienceLevel, allowedCategories, durationMinutes,
                uid, interviewId, callType);

        for (Map<String, Object> qMap : newQs) {
            String text = textValue(qMap.get("question"));
            String cat  = textValue(qMap.get("category"));
            String diff = normalizeDifficulty(textValue(qMap.getOrDefault("difficulty", "medium")));
            String type = "coding".equalsIgnoreCase(textValue(qMap.get("type"))) ? "coding" : "text";
            if ("coding".equals(type) && "hard".equals(diff)) diff = "medium";
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;
            String normalized = normalizeQuestionText(text);
            if (normalized.isBlank() || !seenQuestionTexts.add(normalized)) continue;

            Dto.QuestionDto.QuestionDtoBuilder builder = Dto.QuestionDto.builder()
                    .id("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .type(type);

            // Parse codingData if type is coding
            if ("coding".equals(type)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> codingDataMap = qMap.get("codingData") instanceof Map<?, ?>
                            ? (Map<String, Object>) qMap.get("codingData") : Map.of();
                    if (codingDataMap != null) {
                        String language = textValue(codingDataMap.get("language"));
                        String expectedOutput = textValue(codingDataMap.get("expectedOutput"));
                        String description = textValue(codingDataMap.get("description"));
                        List<?> testCasesList = codingDataMap.get("testCases") instanceof List<?>
                                ? (List<?>) codingDataMap.get("testCases") : List.of();

                        List<Dto.TestCase> testCases = testCasesList.stream()
                                .filter(Map.class::isInstance)
                                .map(Map.class::cast)
                                .map(tc -> Dto.TestCase.builder()
                                    .input(textValue(tc.get("input")))
                                    .expectedOutput(textValue(tc.get("expectedOutput")))
                                    .build())
                                .collect(Collectors.toList());

                        Dto.CodingQuestionData codingData = Dto.CodingQuestionData.builder()
                                .language(defaultLanguageForRole(interviewRole, language))
                                .expectedOutput(expectedOutput)
                                .description(description)
                                .testCases(testCases)
                                .build();
                        builder.question(cleanCodingQuestionText(text, description));
                        builder.codingData(codingData);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse codingData for question: {}", text);
                }
            }

            result.add(builder.build());
        }

        result = enforceCodingPlan(result, interviewRole, durationMinutes, allowedCategories, seenQuestionTexts);

        if (result.isEmpty()) {
            throw new RuntimeException("Could not generate questions. Please try again.");
        }

        log.info("Final question set: {} questions", result.size());
        return result;
    }

    private List<Dto.QuestionDto> enforceCodingPlan(List<Dto.QuestionDto> questions, String role,
                                                     int durationMinutes, List<String> allowedCategories,
                                                     Set<String> seenQuestionTexts) {
        if (!geminiService.roleRequiresCoding(role) || (durationMinutes != 30 && durationMinutes != 60)) {
            return questions.stream()
                    .filter(q -> !"coding".equals(q.getType()))
                    .limit(TARGET_QUESTION_COUNT)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        List<String> required = durationMinutes == 30 ? List.of("easy") : List.of("easy", "medium");
        List<Dto.QuestionDto> textQuestions = questions.stream()
                .filter(q -> !"coding".equals(q.getType()))
                .collect(Collectors.toCollection(ArrayList::new));
        List<Dto.QuestionDto> codingQuestions = new ArrayList<>();

        for (String difficulty : required) {
            Optional<Dto.QuestionDto> match = questions.stream()
                    .filter(q -> "coding".equals(q.getType()))
                    .filter(q -> difficulty.equals(normalizeDifficulty(q.getDifficulty())))
                    .findFirst();
            Dto.QuestionDto coding = match.orElseGet(() ->
                    fallbackCodingQuestion(role, difficulty, allowedCategories, seenQuestionTexts));
            codingQuestions.add(Dto.QuestionDto.builder()
                    .id(coding.getId())
                    .question(coding.getQuestion())
                    .category(coding.getCategory())
                    .difficulty(difficulty)
                    .type("coding")
                    .codingData(coding.getCodingData())
                    .build());
        }

        List<Dto.QuestionDto> planned = new ArrayList<>();
        int firstCodingAt = Math.min(2, textQuestions.size());
        int secondCodingAt = durationMinutes == 60 ? Math.min(6, textQuestions.size()) : -1;
        for (int i = 0; i < textQuestions.size() && planned.size() < TARGET_QUESTION_COUNT; i++) {
            if (planned.size() == firstCodingAt && !codingQuestions.isEmpty()) planned.add(codingQuestions.get(0));
            if (planned.size() == secondCodingAt && codingQuestions.size() > 1) planned.add(codingQuestions.get(1));
            if (planned.size() < TARGET_QUESTION_COUNT) planned.add(textQuestions.get(i));
        }
        for (Dto.QuestionDto coding : codingQuestions) {
            boolean alreadyAdded = planned.stream().anyMatch(q -> q.getId().equals(coding.getId()));
            if (!alreadyAdded && planned.size() < TARGET_QUESTION_COUNT) planned.add(coding);
        }
        return planned.stream().limit(TARGET_QUESTION_COUNT).collect(Collectors.toCollection(ArrayList::new));
    }

    private Dto.QuestionDto fallbackCodingQuestion(String role, String difficulty, List<String> allowedCategories,
                                                   Set<String> seenQuestionTexts) {
        String normalizedRole = geminiService.normalizeRole(role);
        String language = defaultLanguageForRole(normalizedRole, null);
        String category = allowedCategories.stream()
                .filter(c -> !"behavioral".equals(c))
                .findFirst()
                .orElse("problem_solving");
        if ("sql".equals(language)) {
            String question = "Write a SQL query to "
                    + ("medium".equals(difficulty)
                    ? "find the second highest salary from an employees table."
                    : "return each department with its total number of employees.");
            String normalized = normalizeQuestionText(question);
            if (!seenQuestionTexts.add(normalized)) {
                question = "Write a SQL query to "
                        + ("medium".equals(difficulty)
                        ? "find customers who placed more than three orders in the last 30 days."
                        : "return all duplicate email addresses from a users table.");
            }
            List<Dto.TestCase> testCases = "medium".equals(difficulty)
                    ? List.of(
                        Dto.TestCase.builder().input("employees(id, name, salary) with salaries 50000, 70000, 70000, 60000").expectedOutput("60000").build(),
                        Dto.TestCase.builder().input("employees(id, name, salary) with salaries 90000, 80000, 70000").expectedOutput("80000").build())
                    : List.of(
                        Dto.TestCase.builder().input("employees(id, name, department) with Engineering x2 and Sales x1").expectedOutput("Engineering 2, Sales 1").build(),
                        Dto.TestCase.builder().input("employees(id, name, department) with HR x3").expectedOutput("HR 3").build());
            return Dto.QuestionDto.builder()
                    .id("ai_" + UUID.randomUUID())
                    .question(question)
                    .category("sql")
                    .difficulty(difficulty)
                    .type("coding")
                    .codingData(Dto.CodingQuestionData.builder()
                            .language(language)
                            .description(question)
                            .expectedOutput("Return the requested rows or values with correct grouping, filtering, and ordering where needed.")
                            .testCases(testCases)
                            .build())
                    .build();
        }
        String question = "Write a " + languageDisplayName(language) + " function to "
                + ("medium".equals(difficulty)
                ? "find the first non-repeating character in a string and return its index, or -1 if none exists."
                : "return true if a given string is a palindrome after ignoring spaces and letter case.");
        String normalized = normalizeQuestionText(question);
        if (!seenQuestionTexts.add(normalized)) {
            question = "Write a " + languageDisplayName(language) + " function to "
                    + ("medium".equals(difficulty)
                    ? "merge two sorted arrays and return one sorted array without using a built-in sort."
                    : "return the largest number in an integer array.");
        }
        List<Dto.TestCase> testCases = "medium".equals(difficulty)
                ? List.of(
                    Dto.TestCase.builder().input("\"leetcode\"").expectedOutput("0").build(),
                    Dto.TestCase.builder().input("\"aabb\"").expectedOutput("-1").build())
                : List.of(
                    Dto.TestCase.builder().input("\"Nurses Run\"").expectedOutput("true").build(),
                    Dto.TestCase.builder().input("\"hello\"").expectedOutput("false").build());
        return Dto.QuestionDto.builder()
                .id("ai_" + UUID.randomUUID())
                .question(question)
                .category(category)
                .difficulty(difficulty)
                .type("coding")
                .codingData(Dto.CodingQuestionData.builder()
                        .language(language)
                        .description(question)
                        .expectedOutput("Return the requested value for all normal and edge cases.")
                        .testCases(testCases)
                        .build())
                .build();
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String cleanCodingQuestionText(String question, String description) {
        if (description != null && !description.isBlank()) {
            return firstSentence(description);
        }
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) return description == null ? "Solve the coding problem." : firstSentence(description);
        String cleaned = text
                .replaceFirst("(?is)\\s*(problem\\s*description|description|expected\\s*output|test\\s*cases?|examples?)\\s*:.*$", "")
                .trim();
        if (!cleaned.isBlank()) return cleaned;
        return description == null || description.isBlank() ? firstSentence(text) : firstSentence(description);
    }

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) return "Solve the coding problem.";
        String compact = text.replaceAll("\\s+", " ").trim();
        int end = compact.indexOf('.');
        if (end > 12) return compact.substring(0, end + 1);
        return compact.length() > 140 ? compact.substring(0, 140).trim() + "..." : compact;
    }

    private String normalizeDifficulty(String difficulty) {
        String normalized = difficulty == null ? "" : difficulty.trim().toLowerCase(Locale.ROOT);
        if ("easy".equals(normalized) || "medium".equals(normalized)) return normalized;
        if ("hard".equals(normalized)) return "medium";
        return "medium";
    }

    private String defaultLanguageForRole(String role, String requested) {
        if (requested != null && !requested.isBlank()) return requested.trim().toLowerCase(Locale.ROOT);
        String normalized = geminiService.normalizeRole(role);
        if ("java_developer".equals(normalized) || "backend_engineer".equals(normalized)) return "java";
        if ("python_developer".equals(normalized) || "data_scientist".equals(normalized)
                || "ai_engineer".equals(normalized) || "generative_ai_engineer".equals(normalized)
                || "machine_learning_engineer".equals(normalized) || "data_engineer".equals(normalized)) return "python";
        if ("data_analyst".equals(normalized) || "sql_developer".equals(normalized)) return "sql";
        if ("dotnet_developer".equals(normalized) || "csharp_developer".equals(normalized)) return "csharp";
        if ("react_developer".equals(normalized) || "frontend_engineer".equals(normalized)
                || "full_stack_developer".equals(normalized) || "nodejs_developer".equals(normalized)
                || "angular_developer".equals(normalized)) return "javascript";
        if ("qa_automation_engineer".equals(normalized) || "sdet".equals(normalized)) return "java";
        return "java";
    }

    private String languageDisplayName(String language) {
        return switch (language) {
            case "javascript" -> "JavaScript";
            case "python" -> "Python";
            case "sql" -> "SQL";
            case "cpp" -> "C++";
            case "csharp" -> "C#";
            default -> "Java";
        };
    }

    private Interview.CodingQuestionData toInterviewCodingData(Dto.CodingQuestionData codingData) {
        if (codingData == null) return null;
        List<Interview.TestCase> testCases = codingData.getTestCases() == null ? List.of()
                : codingData.getTestCases().stream()
                    .map(tc -> Interview.TestCase.builder()
                            .input(tc.getInput())
                            .expectedOutput(tc.getExpectedOutput())
                            .build())
                    .collect(Collectors.toList());
        return Interview.CodingQuestionData.builder()
                .language(codingData.getLanguage())
                .expectedOutput(codingData.getExpectedOutput())
                .description(codingData.getDescription())
                .testCases(testCases)
                .build();
    }

    private Dto.CodingQuestionData toDtoCodingData(Interview.CodingQuestionData codingData) {
        if (codingData == null) return null;
        List<Dto.TestCase> testCases = codingData.getTestCases() == null ? List.of()
                : codingData.getTestCases().stream()
                    .map(tc -> Dto.TestCase.builder()
                            .input(tc.getInput())
                            .expectedOutput(tc.getExpectedOutput())
                            .build())
                    .collect(Collectors.toList());
        return Dto.CodingQuestionData.builder()
                .language(codingData.getLanguage())
                .expectedOutput(codingData.getExpectedOutput())
                .description(codingData.getDescription())
                .testCases(testCases)
                .build();
    }
    // -- Submit Answer --
    public Dto.NextQuestionResponse nextQuestion(String uid, Dto.NextQuestionRequest req) {
        Interview interview = interviewRepository.findById(req.getInterviewId())
                .orElseThrow(() -> new RuntimeException("Interview not found"));
        if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");
        if (!"STARTED".equals(interview.getStatus())) {
            throw new RuntimeException("Interview is not in progress");
        }

        int pooledIndex = interviewRepository.moveNextPooledQuestionToAsked(interview.getId());
        if (pooledIndex >= 0) {
            Interview refreshed = interviewRepository.findById(interview.getId())
                    .orElseThrow(() -> new RuntimeException("Interview not found"));
            return Dto.NextQuestionResponse.builder()
                    .question(toQuestionDto(refreshed.getQuestions().get(pooledIndex)))
                    .questionIndex(pooledIndex)
                    .build();
        }

        List<Interview.QuestionAnswer> existing = interview.getQuestions() != null
                ? interview.getQuestions() : List.of();
        List<String> existingTexts = new ArrayList<>(collectHistoricalQuestionTexts(uid, interview.getId()));
        existing.stream()
                .map(Interview.QuestionAnswer::getQuestion)
                .filter(Objects::nonNull)
                .forEach(existingTexts::add);

        User user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<String> allowedCategories = geminiService.categoriesForInterview(
                interview.getInterviewRole(), user.getResumeCategories());
        List<Map<String, Object>> generated = geminiService.generateQuestions(
                interview.getResumeSummary(), existingTexts, TARGET_QUESTION_COUNT,
                interview.getInterviewRole(), interview.getExperienceLevel(), allowedCategories,
                0, uid, interview.getId(), "next_question_generation");

        List<Interview.QuestionAnswer> freshPool = new ArrayList<>();
        Set<String> seen = existingTexts.stream()
                .map(this::normalizeQuestionText)
                .collect(Collectors.toCollection(HashSet::new));
        for (Map<String, Object> qMap : generated) {
            String text = textValue(qMap.get("question"));
            String cat = textValue(qMap.get("category"));
            String diff = normalizeDifficulty(textValue(qMap.getOrDefault("difficulty", "medium")));
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;
            String normalized = normalizeQuestionText(text);
            if (normalized.isBlank() || !seen.add(normalized)) continue;
            freshPool.add(Interview.QuestionAnswer.builder()
                    .questionId("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .type("text")
                    .answer("")
                    .feedback("")
                    .build());
        }
        if (freshPool.isEmpty()) throw new RuntimeException("Could not generate the next question. Please try again.");

        interviewRepository.appendQuestionsToPool(interview.getId(), freshPool);
        return nextQuestion(uid, req);
    }

    private List<String> collectHistoricalQuestionTexts(String uid, String excludeInterviewId) {
        return interviewRepository.findRecentCompletedByUserId(uid, HISTORICAL_INTERVIEW_LOOKBACK).stream()
                .filter(i -> excludeInterviewId == null || !excludeInterviewId.equals(i.getId()))
                .filter(i -> i.getQuestions() != null)
                .flatMap(i -> i.getQuestions().stream())
                .map(Interview.QuestionAnswer::getQuestion)
                .filter(Objects::nonNull)
                .filter(q -> !q.isBlank())
                .distinct()
                .limit(80)
                .collect(Collectors.toList());
    }

    private Dto.QuestionDto toQuestionDto(Interview.QuestionAnswer qa) {
        return Dto.QuestionDto.builder()
                .id(qa.getQuestionId())
                .question(qa.getQuestion())
                .category(qa.getCategory())
                .difficulty(qa.getDifficulty())
                .type(qa.getType() != null ? qa.getType() : "text")
                .codingData(toDtoCodingData(qa.getCodingData()))
                .build();
    }

    private List<Interview.QuestionAnswer> askedQuestionsOnly(List<Interview.QuestionAnswer> questions) {
        if (questions == null) return List.of();
        return questions.stream()
                .filter(q -> q.getQuestion() != null && !q.getQuestion().isBlank())
                .collect(Collectors.toList());
    }

    private String normalizeQuestionText(String question) {
        if (question == null) return "";
        return question.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public Dto.SubmitAnswerResponse submitAnswer(String uid, Dto.SubmitAnswerRequest req) {
        Interview.AnswerTrace trace = Interview.AnswerTrace.builder()
                .source("browser_text")
                .transcriptionStatus("not_available")
                .browserTranscript(req.getAnswer() != null ? req.getAnswer().trim() : "")
                .build();
        return submitAnswer(uid, req, trace);
    }

    private Dto.SubmitAnswerResponse submitAnswer(String uid, Dto.SubmitAnswerRequest req, Interview.AnswerTrace trace) {
        Interview interview = interviewRepository.findById(req.getInterviewId())
                .orElseThrow(() -> new RuntimeException("Interview not found"));

        if (!interview.getUserId().equals(uid)) {
            throw new RuntimeException("Unauthorized");
        }
        if (!"STARTED".equals(interview.getStatus())) {
            throw new RuntimeException("Interview is not in progress");
        }

        int idx = req.getQuestionIndex();
        List<Interview.QuestionAnswer> questions = interview.getQuestions();
        if (questions == null || idx < 0 || idx >= questions.size()) {
            throw new RuntimeException("Invalid question index: " + idx);
        }

        Interview.QuestionAnswer currentQ = questions.get(idx);
        if (req.getQuestionId() != null && !req.getQuestionId().isBlank()
                && currentQ.getQuestionId() != null
                && !req.getQuestionId().equals(currentQ.getQuestionId())) {
            log.warn("Question mismatch for interview {}: index={} requestQuestionId={} actualQuestionId={}",
                    req.getInterviewId(), idx, req.getQuestionId(), currentQ.getQuestionId());
            throw new RuntimeException("Question mismatch. Please retry the current question.");
        }

        String answer = req.getAnswer() != null ? req.getAnswer().trim() : "";

        if (answer.isBlank()) {
            // User skipped — give generic feedback
            String skipFeedback = "It looks like you didn't get a chance to answer that one. "
                    + "That's okay — let's keep moving. Try to give at least a brief answer "
                    + "even if you're unsure, as it helps me understand your thought process.";
            Interview.AnswerTrace skippedTrace = trace != null ? trace : Interview.AnswerTrace.builder().build();
            skippedTrace.setSource("skipped");
            skippedTrace.setFinalTranscript("(skipped)");
            skippedTrace.setCorrectedAt(System.currentTimeMillis());
            interviewRepository.updateAnswerAndFeedback(req.getInterviewId(), idx, "(skipped)", skipFeedback, skippedTrace);
            return Dto.SubmitAnswerResponse.builder()
                    .feedback(skipFeedback)
                    .answer("(skipped)")
                    .isLastQuestion(idx == questions.size() - 1)
                    .build();
        }

        boolean fromAudioTranscription = trace != null && "audio_transcription".equals(trace.getSource());
        if (!fromAudioTranscription) {
            try {
                String correctedAnswer = geminiService.correctTranscript(answer, uid, interview.getId());
                if (correctedAnswer != null && !correctedAnswer.isBlank()) {
                    answer = correctedAnswer.trim();
                }
            } catch (GeminiService.GeminiQuotaException | GeminiService.GeminiUnavailableException e) {
                log.warn("Transcript correction unavailable for interview {} question {}: {}",
                        req.getInterviewId(), idx, e.getMessage());
            } catch (Exception e) {
                log.warn("Transcript correction failed for interview {} question {}: {}",
                        req.getInterviewId(), idx, e.getMessage());
            }
        }
        if (trace != null) {
            trace.setFinalTranscript(answer);
            trace.setCorrectedAt(System.currentTimeMillis());
        }

        // Previous Q&A for conversational context
        String prevQuestion = idx > 0 ? questions.get(idx - 1).getQuestion() : null;
        String prevAnswer   = idx > 0 ? questions.get(idx - 1).getAnswer() : null;

        String feedback;
        try {
            feedback = geminiService.generateFeedback(
                    currentQ.getQuestion(), currentQ.getCategory(),
                    answer, prevQuestion, prevAnswer,
                    interview.getInterviewRole(), interview.getExperienceLevel(),
                    uid, interview.getId());
        } catch (GeminiService.GeminiQuotaException e) {
            feedback = "Thanks, I got your answer. The AI feedback service is temporarily busy, so I'll save this response and keep the interview moving. Try to keep your next answer direct, structured, and supported with one concrete example.";
        } catch (GeminiService.GeminiUnavailableException e) {
            feedback = "Thanks, I got your answer. The AI feedback service is temporarily unavailable, so I'll save this response and keep the interview moving. Try to answer with a clear point, one concrete example, and the tradeoffs.";
        } catch (Exception e) {
            log.warn("Feedback generation failed for interview {} question {}: {}",
                    req.getInterviewId(), idx, e.getMessage());
            feedback = "Thanks, I got your answer. I could not generate detailed feedback for this one, but your response is saved. Let's keep the interview moving.";
        }

        // Persist to Firestore
        interviewRepository.updateAnswerAndFeedback(req.getInterviewId(), idx, answer, feedback, trace);

        return Dto.SubmitAnswerResponse.builder()
                .feedback(feedback)
                .answer(answer)
                .isLastQuestion(idx == questions.size() - 1)
                .build();
    }

    // ── Submit Audio Answer ──
    public Dto.SubmitAnswerResponse submitAudioAnswer(String uid,
                                                      String interviewId,
                                                      String questionId,
                                                      int questionIndex,
                                                      String fallbackAnswer,
                                                      MultipartFile audio) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found"));

        if (!interview.getUserId().equals(uid)) {
            throw new RuntimeException("Unauthorized");
        }

        List<Interview.QuestionAnswer> questions = interview.getQuestions();
        if (questions == null || questionIndex < 0 || questionIndex >= questions.size()) {
            throw new RuntimeException("Invalid question index: " + questionIndex);
        }

        Interview.QuestionAnswer currentQ = questions.get(questionIndex);
        String browserTranscript = fallbackAnswer != null ? fallbackAnswer.trim() : "";
        String answer = browserTranscript;
        Interview.AnswerTrace trace = Interview.AnswerTrace.builder()
                .source("browser_text")
                .transcriptionStatus("not_available")
                .browserTranscript(browserTranscript)
                .build();

        if (audio != null && !audio.isEmpty()) {
            trace.setAudioBytes(audio.getSize());
            try {
                String mimeType = audio.getContentType();
                if (mimeType == null || mimeType.isBlank()) {
                    mimeType = "audio/webm";
                }
                trace.setAudioMimeType(mimeType);
                String transcript = geminiService.transcribeAnswerAudio(
                        audio.getBytes(),
                        mimeType,
                        currentQ.getQuestion(),
                        interview.getInterviewRole(),
                        interview.getExperienceLevel(),
                        uid,
                        interviewId);
                if (transcript != null && !transcript.isBlank()) {
                    answer = transcript.trim();
                    trace.setSource("audio_transcription");
                    trace.setTranscriptionStatus("success");
                    trace.setAudioTranscript(answer);
                    trace.setTranscribedAt(System.currentTimeMillis());
                } else {
                    trace.setSource(browserTranscript.isBlank() ? "skipped" : "browser_fallback");
                    trace.setTranscriptionStatus("empty");
                    trace.setTranscribedAt(System.currentTimeMillis());
                }
            } catch (GeminiService.GeminiQuotaException | GeminiService.GeminiUnavailableException e) {
                trace.setSource(browserTranscript.isBlank() ? "skipped" : "browser_fallback");
                trace.setTranscriptionStatus("failed");
                trace.setError(e.getMessage());
                log.warn("Audio transcription unavailable for interview {} question {}: {}",
                        interviewId, questionIndex, e.getMessage());
            } catch (Exception e) {
                trace.setSource(browserTranscript.isBlank() ? "skipped" : "browser_fallback");
                trace.setTranscriptionStatus("failed");
                trace.setError(e.getMessage());
                log.warn("Audio transcription failed for interview {} question {}: {}",
                        interviewId, questionIndex, e.getMessage());
            }
        }

        return submitAnswer(uid, Dto.SubmitAnswerRequest.builder()
                .interviewId(interviewId)
                .questionId(questionId)
                .questionIndex(questionIndex)
                .answer(answer)
                .build(), trace);
    }

    // ── Submit Coding Answer ──
    public Dto.SubmitCodingAnswerResponse submitCodingAnswer(String uid, Dto.SubmitCodingAnswerRequest req) {
        Interview interview = interviewRepository.findById(req.getInterviewId())
                .orElseThrow(() -> new RuntimeException("Interview not found"));

        if (!interview.getUserId().equals(uid)) {
            throw new RuntimeException("Unauthorized");
        }
        if (!"STARTED".equals(interview.getStatus())) {
            throw new RuntimeException("Interview is not in progress");
        }

        int idx = req.getQuestionIndex();
        List<Interview.QuestionAnswer> questions = interview.getQuestions();
        if (questions == null || idx < 0 || idx >= questions.size()) {
            throw new RuntimeException("Invalid question index: " + idx);
        }

        Interview.QuestionAnswer currentQ = questions.get(idx);
        if (!"coding".equals(currentQ.getType())) {
            throw new RuntimeException("This question is not a coding question");
        }

        String code = req.getCode() != null ? req.getCode().trim() : "";
        String language = req.getLanguage();
        long timeTakenMs = req.getTimeTakenMs();

        String executionResult = executeCode(code, language, currentQ);
        Map<String, Object> evaluation = evaluateCodingSolution(code, language, executionResult, currentQ,
                interview.getInterviewRole(), interview.getExperienceLevel(), uid, interview.getId());
        String aiEvaluation = String.valueOf(evaluation.getOrDefault("summary", "Code review completed."));
        int score = calculateCodingScore(evaluation);
        boolean isCorrect = score >= 80; // 80% or above considered correct

        // Store coding submission
        Interview.CodingSubmission codingSubmission = Interview.CodingSubmission.builder()
                .language(language)
                .code(code)
                .executionResult(executionResult)
                .aiEvaluation(aiEvaluation)
                .score(score)
                .timeTakenMs(timeTakenMs)
                .submittedAt(System.currentTimeMillis())
                .build();

        interviewRepository.updateCodingSubmission(req.getInterviewId(), idx, codingSubmission);

        String feedback = generateCodingFeedback(aiEvaluation, score, timeTakenMs);

        return Dto.SubmitCodingAnswerResponse.builder()
                .executionResult(executionResult)
                .aiEvaluation(aiEvaluation)
                .score(score)
                .isCorrect(isCorrect)
                .feedback(feedback)
                .isLastQuestion(idx == questions.size() - 1)
                .build();
    }

    private String executeCode(String code, String language, Interview.QuestionAnswer question) {
        return "Not executed on server. Submission reviewed by AI against the prompt and test-case expectations.";
    }

    private Map<String, Object> evaluateCodingSolution(String code, String language, String executionResult,
                                                       Interview.QuestionAnswer question, String role,
                                                       String experienceLevel, String uid, String interviewId) {
        if (code.isBlank()) {
            return Map.of(
                    "correctness", 0,
                    "logic", 0,
                    "syntax", 0,
                    "optimization", 0,
                    "edgeCases", 0,
                    "overall", 0,
                    "summary", "No code was submitted, so the solution cannot be evaluated.");
        }

        String tests = "";
        if (question.getCodingData() != null && question.getCodingData().getTestCases() != null) {
            tests = question.getCodingData().getTestCases().stream()
                    .map(tc -> "Input: " + tc.getInput() + " | Expected: " + tc.getExpectedOutput())
                    .collect(Collectors.joining("\n"));
        }
        String prompt = """
                Evaluate this coding-round submission for a mock interview.

                Role: %s
                Experience level: %s
                Difficulty: %s
                Language: %s
                Question: %s
                Description: %s
                Expected output: %s
                Test cases:
                %s

                Candidate code:
                ```%s
                %s
                ```

                Server execution status: %s

                Return ONLY valid JSON:
                {
                  "correctness": 0,
                  "logic": 0,
                  "syntax": 0,
                  "optimization": 0,
                  "edgeCases": 0,
                  "overall": 0,
                  "summary": "2-4 sentence interview-style evaluation covering correctness, logic, syntax, optimization, and edge cases"
                }
                Score each numeric field 0-100. Be strict but fair. Do not give credit for code that does not address the prompt.
                """.formatted(
                geminiService.roleLabel(role),
                geminiService.experienceLabel(experienceLevel),
                question.getDifficulty(),
                language,
                question.getQuestion(),
                question.getCodingData() != null ? question.getCodingData().getDescription() : "",
                question.getCodingData() != null ? question.getCodingData().getExpectedOutput() : "",
                tests.isBlank() ? "none" : tests,
                language,
                truncate(code, 6000),
                executionResult
        );
        try {
            return geminiService.parseJsonObjectOrThrow(geminiService.callGeminiWithTemp(
                    prompt,
                    "You are a senior coding interviewer. Evaluate only the submitted code against the prompt. Return only JSON.",
                    0.2, uid, interviewId, "coding_evaluation"));
        } catch (Exception e) {
            log.warn("Coding evaluation fallback for interview {}: {}", interviewId, e.getMessage());
            return fallbackCodingEvaluation(code);
        }
    }

    private Map<String, Object> fallbackCodingEvaluation(String code) {
        int syntax = code.contains(";") || code.contains("def ") || code.contains("function ") ? 55 : 35;
        int logic = code.length() > 80 ? 45 : 25;
        int overall = Math.min(60, Math.round((syntax + logic) / 2.0f));
        return Map.of(
                "correctness", Math.max(20, overall - 10),
                "logic", logic,
                "syntax", syntax,
                "optimization", Math.max(20, overall - 15),
                "edgeCases", Math.max(15, overall - 20),
                "overall", overall,
                "summary", "The AI coding evaluator was temporarily unavailable, so this is a conservative fallback review. The submission was saved, but the score is capped until a detailed AI review can be generated.");
    }

    private int calculateCodingScore(Map<String, Object> evaluation) {
        int explicit = toInt(evaluation.get("overall"), -1);
        if (explicit >= 0) return clampScore(explicit);
        int correctness = toInt(evaluation.get("correctness"), 0);
        int logic = toInt(evaluation.get("logic"), 0);
        int syntax = toInt(evaluation.get("syntax"), 0);
        int optimization = toInt(evaluation.get("optimization"), 0);
        int edgeCases = toInt(evaluation.get("edgeCases"), 0);
        return clampScore(Math.round(correctness * 0.35f + logic * 0.25f + syntax * 0.15f
                + optimization * 0.10f + edgeCases * 0.15f));
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String generateCodingFeedback(String aiEvaluation, int score, long timeTakenMs) {
        return aiEvaluation + " Score: " + score + "/100. Time taken: " + (timeTakenMs / 1000) + "s";
    }

    // ── Complete Interview ──
    public Dto.CompleteInterviewResponse completeInterview(String uid, String interviewId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found"));

        if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");
        if ("COMPLETED".equals(interview.getStatus())) {
            // Already completed — return existing scores
            return Dto.CompleteInterviewResponse.builder()
                    .interviewId(interviewId)
                    .completedAt(interview.getCompletedAt())
                    .scores(toScoresDto(interview.getScores()))
                    .status("COMPLETED")
                    .build();
        }
        if ("ANALYSIS_PENDING".equals(interview.getStatus())) {
            log.info("Retrying pending interview analysis for {}", interviewId);
        }

        List<Interview.QuestionAnswer> qas = askedQuestionsOnly(interview.getQuestions());
        if (qas == null || qas.isEmpty()) throw new RuntimeException("No questions in interview");

        // Build Q&A list for scoring
        List<Map<String, String>> qaList = qas.stream()
                .map(q -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("question", q.getQuestion());
                    m.put("category", q.getCategory() != null ? q.getCategory() : "problem_solving");
                    if ("coding".equals(q.getType()) && q.getCodingSubmission() != null) {
                        Interview.CodingSubmission cs = q.getCodingSubmission();
                        m.put("answer", "Coding submission in " + cs.getLanguage()
                                + "\nCode:\n" + truncate(cs.getCode(), 1800)
                                + "\nEvaluation:\n" + truncate(cs.getAiEvaluation(), 900)
                                + "\nScore: " + cs.getScore() + "/100");
                    } else {
                        m.put("answer", q.getAnswer() != null && !q.getAnswer().isBlank()
                                ? q.getAnswer() : "(no answer)");
                    }
                    return m;
                })
                .collect(Collectors.toList());

        // Calculate scores
        User user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<String> allowedCategories = geminiService.categoriesForInterview(
                interview.getInterviewRole(), user.getResumeCategories());
        Map<String, Object> rawScores;
        try {
            rawScores = geminiService.calculateScores(
                    qaList, interview.getInterviewRole(), interview.getExperienceLevel(), allowedCategories,
                    uid, interviewId);
        } catch (GeminiService.GeminiQuotaException | GeminiService.GeminiUnavailableException e) {
            long pendingAt = System.currentTimeMillis();
            interviewRepository.markAnalysisPending(interviewId, qas, pendingAt, ANALYSIS_PENDING_MESSAGE);
            return Dto.CompleteInterviewResponse.builder()
                    .interviewId(interviewId)
                    .completedAt(pendingAt)
                    .status("ANALYSIS_PENDING")
                    .message(ANALYSIS_PENDING_MESSAGE)
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Integer> categories = convertCategoryScores(
                (Map<String, Object>) rawScores.getOrDefault("categories", Map.of()));

        Interview.Scores scores = Interview.Scores.builder()
                .overall(toInt(rawScores.get("overall"), 0))
                .technical(toInt(rawScores.get("technical"), 0))
                .communication(toInt(rawScores.get("communication"), 0))
                .problemSolving(toInt(rawScores.get("problemSolving"), 0))
                .roleDepth(toInt(rawScores.get("roleDepth"), 0))
                .categories(categories)
                .build();

        long completedAt = System.currentTimeMillis();

        interviewRepository.completeInterview(interviewId, scores, completedAt);

        // Update user stats
        List<Interview> allCompleted = interviewRepository.findAllCompletedByUserId(uid);
        userRepository.updateStats(uid, scores.getOverall(), allCompleted.size());

        log.info("Interview {} completed. Score: {}%", interviewId, scores.getOverall());

        return Dto.CompleteInterviewResponse.builder()
                .interviewId(interviewId)
                .completedAt(completedAt)
                .scores(toScoresDto(scores))
                .status("COMPLETED")
                .build();
    }

    // ── History ──
    public List<Dto.InterviewHistoryItem> getHistory(String uid) {
        List<Interview> interviews = interviewRepository.findReportableByUserId(uid, 10);
        boolean retriedPending = false;
        long now = System.currentTimeMillis();
        for (Interview interview : interviews) {
            if ("ANALYSIS_PENDING".equals(interview.getStatus())
                    && interview.getAnalysisRetryAfter() <= now) {
                retriedPending = true;
                try {
                    completeInterview(uid, interview.getId());
                } catch (Exception e) {
                    log.info("Pending analysis retry is still unavailable for interview {}: {}",
                            interview.getId(), e.getMessage());
                }
            }
        }
        if (retriedPending) {
            interviews = interviewRepository.findReportableByUserId(uid, 10);
        }
        return interviews.stream().map(i -> {
            List<String> cats = List.of();
            if (i.getQuestions() != null) {
                cats = i.getQuestions().stream()
                        .map(Interview.QuestionAnswer::getCategory)
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(3)
                        .collect(Collectors.toList());
            }
            long ts = i.getCompletedAt() > 0 ? i.getCompletedAt() : i.getStartedAt();
            return Dto.InterviewHistoryItem.builder()
                    .id(i.getId())
                    .date(DATE_FMT.format(Instant.ofEpochMilli(ts)))
                    .status(i.getStatus())
                    .message(i.getCompletionMessage())
                    .interviewRole(i.getInterviewRole())
                    .experienceLevel(i.getExperienceLevel())
                    .durationMinutes(i.getDurationMinutes())
                    .scores(toScoresDto(i.getScores()))
                    .questionCount(i.getQuestions() != null ? i.getQuestions().size() : 0)
                    .categories(cats)
                    .build();
        }).collect(Collectors.toList());
    }

    public Dto.InterviewDetailResponse getDetail(String uid, String interviewId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found"));
        if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");

        List<QADetailDto> qaList = interview.getQuestions() == null ? List.of() :
                interview.getQuestions().stream()
                        .map(q -> Dto.QADetailDto.builder()
                                .question(q.getQuestion())
                                .category(q.getCategory())
                                .difficulty(q.getDifficulty())
                                .type(q.getType() != null ? q.getType() : "text")
                                .codingData(toDtoCodingData(q.getCodingData()))
                                .answer(q.getAnswer() != null ? q.getAnswer() : "")
                                .feedback(q.getFeedback() != null ? q.getFeedback() : "")
                                .answerTrace(toAnswerTraceDetail(q.getAnswerTrace()))
                                .codingSubmission(toCodingSubmissionDetail(q.getCodingSubmission()))
                                .build())
                        .collect(Collectors.toList());

        long ts = interview.getCompletedAt() > 0
                ? interview.getCompletedAt() : interview.getStartedAt();
        return Dto.InterviewDetailResponse.builder()
                .id(interview.getId())
                .date(DATE_FMT.format(Instant.ofEpochMilli(ts)))
                .status(interview.getStatus())
                .message(interview.getCompletionMessage())
                .interviewRole(interview.getInterviewRole())
                .experienceLevel(interview.getExperienceLevel())
                .durationMinutes(interview.getDurationMinutes())
                .scores(toScoresDto(interview.getScores()))
                .questions(qaList)
                .build();
    }

    private Dto.AnswerTraceDetail toAnswerTraceDetail(Interview.AnswerTrace trace) {
        if (trace == null) return null;
        return Dto.AnswerTraceDetail.builder()
                .source(trace.getSource())
                .transcriptionStatus(trace.getTranscriptionStatus())
                .browserTranscript(trace.getBrowserTranscript())
                .audioTranscript(trace.getAudioTranscript())
                .finalTranscript(trace.getFinalTranscript())
                .audioMimeType(trace.getAudioMimeType())
                .audioBytes(trace.getAudioBytes())
                .transcribedAt(trace.getTranscribedAt())
                .correctedAt(trace.getCorrectedAt())
                .error(trace.getError())
                .build();
    }

    public Dto.PerformanceAnalysisResponse getDetailAnalysis(String uid, String interviewId) {
      Interview interview = interviewRepository.findById(interviewId)
              .orElseThrow(() -> new RuntimeException("Interview not found"));
      if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");
      if (interview.getAnalysis() != null) {
          return toAnalysisResponse(interview.getAnalysis(),
                  interview.getScores() != null ? interview.getScores().getOverall() : 0);
      }

      List<Interview.QuestionAnswer> qas = interview.getQuestions() != null
              ? interview.getQuestions() : List.of();
      if (qas.isEmpty()) {
          return buildSingleInterviewFallback(interview, "No questions were found for this interview.");
      }

      int score = interview.getScores() != null ? interview.getScores().getOverall() : 0;

      // ── Build rich context ──────────────────────────────────────────────────
      StringBuilder context = new StringBuilder();
      context.append("SINGLE SESSION INTERVIEW DATA\n");
      context.append("==============================\n\n");
      context.append("Role: ").append(geminiService.roleLabel(interview.getInterviewRole())).append("\n");
      context.append("Experience Level: ").append(geminiService.experienceLabel(interview.getExperienceLevel())).append("\n");

      if (interview.getScores() != null) {
          context.append("Scores — Overall: ").append(score).append("%, ");
          context.append("Technical: ").append(interview.getScores().getTechnical()).append("%, ");
          context.append("Communication: ").append(interview.getScores().getCommunication()).append("%, ");
          context.append("Problem Solving: ").append(interview.getScores().getProblemSolving()).append("%\n\n");
      }

      context.append("QUESTIONS & ANSWERS:\n");
      context.append("====================\n\n");
      for (Interview.QuestionAnswer q : qas) {
          context.append("Q [").append(q.getCategory()).append("]: ").append(q.getQuestion()).append("\n");
          if ("coding".equals(q.getType()) && q.getCodingSubmission() != null) {
              context.append("Language: ").append(q.getCodingSubmission().getLanguage()).append("\n");
              context.append("Code Score: ").append(q.getCodingSubmission().getScore()).append("/100\n");
              context.append("Code Evaluation: ").append(q.getCodingSubmission().getAiEvaluation()).append("\n");
          } else {
              String answer = q.getAnswer() == null || q.getAnswer().isBlank()
                      ? "(no answer)" : q.getAnswer().trim();
              context.append("A: ").append(answer).append("\n");
          }
          if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
              context.append("Live feedback: ").append(q.getFeedback()).append("\n");
          }
          context.append("\n");
      }

      // ── System prompt ───────────────────────────────────────────────────────
      String systemPrompt =
              "You are Sarah, a senior interviewer with 15+ years of hiring across all levels. " +
              "You just finished interviewing this candidate and are giving them direct post-interview feedback.\n\n" +

              "TONE REQUIREMENTS:\n" +
              "- Always say 'you' and 'your' — never say 'the candidate'\n" +
              "- Be honest but encouraging — name real gaps without being discouraging\n" +
              "- Reference their actual answers specifically — no generic advice that could apply to anyone\n" +
              "- Sound like a real person talking after an interview, not a corporate performance review\n" +
              "- Warm and professional — like someone who genuinely wants them to succeed\n\n" +

              "CRITICAL CONSTRAINTS:\n" +
              "- Verdict MUST match the score:\n" +
              "  • 75%+ = STRONG HIRE\n" +
              "  • 45–74% = HIRE WITH COACHING\n" +
              "  • 30–44% = NOT YET\n" +
              "  • <30% = REJECT\n" +
              "- Only mention strengths that are actually visible in their answers — never invent them\n" +
              "- Improvement areas must be specific and time-bound — not vague like 'practice more'\n" +
              "- Distinguish between knowledge gaps, communication gaps, and confidence issues\n\n" +

              "DO NOT:\n" +
              "- Use bullet points, numbered lists, or markdown in any field value\n" +
              "- Wrap any response in square brackets [ ]\n" +
              "- Use placeholder text or instructions as the value — write the actual feedback\n" +
              "- Use jargon like 'leverage', 'synergize', or 'deep-dive'\n" +
              "- Contradict yourself between fields — score, strengths, and verdict must all align\n\n" +

              "OUTPUT RULES:\n" +
              "- Return ONLY valid JSON — no markdown, no preamble, no explanation outside the JSON\n" +
              "- Every field value must be flowing prose — never an array, never a bracketed placeholder\n" +
              "- Fill every field with real, specific feedback based on this candidate's actual answers";

      // ── User prompt ─────────────────────────────────────────────────────────
      String userPrompt = context +
              "\n\n=== YOUR FEEDBACK TASK ===\n" +
              "You just interviewed this person. Give them real, specific post-interview feedback.\n" +
              "Reference what they actually said. Be honest about gaps. Acknowledge genuine strengths.\n\n" +

              "FIELD INSTRUCTIONS:\n" +
              "- overallAnalysis: 3-4 sentences on how this session went. Reference their actual answers.\n" +
              "- communicationAnalysis: 2-3 sentences on clarity, confidence, and how easy they were to follow. Give examples.\n" +
              "- answeringFlowAnalysis: 2-3 sentences on structure — did they get to the point or ramble? Use evidence.\n" +
              "- strengthsSummary: 2-3 sentences on real strengths from this session. Specific, not generic.\n" +
              "- improvementPlan: 3 actionable, time-bound things to work on. Written as natural sentences, not a list.\n" +
              "- interviewerVerdict: Start with the verdict label (STRONG HIRE / HIRE WITH COACHING / NOT YET / REJECT), then 1-2 sentences of honest reasoning. Must match the score.\n\n" +

              "Return ONLY this JSON. All values must be plain prose strings — no brackets, no arrays, no markdown:\n" +
              "{\n" +
              "  \"overallAnalysis\": \"\",\n" +
              "  \"communicationAnalysis\": \"\",\n" +
              "  \"answeringFlowAnalysis\": \"\",\n" +
              "  \"strengthsSummary\": \"\",\n" +
              "  \"improvementPlan\": \"\",\n" +
              "  \"interviewerVerdict\": \"\"\n" +
              "}\n";

      // ── Call Gemini + parse ─────────────────────────────────────────────────
      try {
          Map<String, Object> raw = geminiService.parseJsonObjectOrThrow(
                  geminiService.callGeminiWithTemp(userPrompt, systemPrompt, 0.55,
                          uid, interviewId, "single_interview_analysis"));

          Interview.Analysis analysis = Interview.Analysis.builder()
                  .overallAnalysis(extractString(raw, "overallAnalysis"))
                  .communicationAnalysis(extractString(raw, "communicationAnalysis"))
                  .answeringFlowAnalysis(extractString(raw, "answeringFlowAnalysis"))
                  .strengthsSummary(extractString(raw, "strengthsSummary"))
                  .improvementPlan(extractString(raw, "improvementPlan"))
                  .interviewerVerdict(alignVerdict(extractString(raw, "interviewerVerdict"), score))
                  .generatedAt(System.currentTimeMillis())
                  .build();

          interviewRepository.updateAnalysis(interviewId, analysis);
          return toAnalysisResponse(analysis, score);

      } catch (Exception e) {
          log.error("Single interview analysis failed: {}", e.getMessage());
          return buildSingleInterviewFallback(interview, "AI analysis is temporarily unavailable. Please try again.");
      }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private String extractString(Map<String, Object> map, String key) {
      Object val = map.get(key);
      if (val == null) return "";
      if (val instanceof List<?> list) {
          return list.stream().map(Object::toString).collect(Collectors.joining(" "));
      }
      return stripBrackets(val.toString().trim());
  }

  private String stripBrackets(String text) {
      if (text == null || text.isBlank()) return text;
      String t = text.trim();
      if (t.startsWith("[") && t.endsWith("]")
              && !t.matches("^\\[(?:STRONG HIRE|HIRE WITH COACHING|NOT YET|REJECT)\\].*")) {
          t = t.substring(1, t.length() - 1).trim();
      }
      return t;
  }

  private String alignVerdict(String verdict, int score) {
      String expected = score >= 75 ? "STRONG HIRE"
                      : score >= 45 ? "HIRE WITH COACHING"
                      : score >= 30 ? "NOT YET"
                      : "REJECT";
      if (verdict != null && verdict.toUpperCase().contains(expected)) return verdict;
      log.warn("Verdict-score mismatch. Score: {}, Verdict: '{}'. Correcting to: {}", score, verdict, expected);
      String reasoning = score >= 75
              ? "Your technical knowledge and communication are solid. You're ready to contribute from day one."
              : score >= 45
              ? "You have the fundamentals. A few weeks of focused practice will get you to the next level."
              : score >= 30
              ? "You're still building. Come back after 8–12 weeks of focused study — this is coachable."
              : "There are significant gaps right now. Take this feedback seriously and revisit in a few months.";
      return expected + ": " + reasoning;
  }
    public Dto.PerformanceAnalysisResponse getDetailAnalysis111111111111(String uid, String interviewId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found"));
        if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");
        if (interview.getAnalysis() != null) {
            return toAnalysisResponse(interview.getAnalysis(),
                    interview.getScores() != null ? interview.getScores().getOverall() : 0);
        }

        List<Interview.QuestionAnswer> qas = interview.getQuestions() != null
                ? interview.getQuestions() : List.of();
        if (qas.isEmpty()) {
            return buildSingleInterviewFallback(interview, "No questions were found for this interview.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Analyze this single mock interview session.\n");
        sb.append("Target role: ").append(geminiService.roleLabel(interview.getInterviewRole())).append("\n");
        sb.append("Experience level: ").append(geminiService.experienceLabel(interview.getExperienceLevel())).append("\n");
        if (interview.getScores() != null) {
            sb.append("Scores: overall=").append(interview.getScores().getOverall())
                    .append(", technical=").append(interview.getScores().getTechnical())
                    .append(", communication=").append(interview.getScores().getCommunication())
                    .append(", problemSolving=").append(interview.getScores().getProblemSolving())
                    .append("\n\n");
        }
        for (Interview.QuestionAnswer q : qas) {
            sb.append("Category: ").append(q.getCategory()).append("\n");
            sb.append("Question: ").append(q.getQuestion()).append("\n");
            if ("coding".equals(q.getType()) && q.getCodingSubmission() != null) {
                sb.append("Coding language: ").append(q.getCodingSubmission().getLanguage()).append("\n");
                sb.append("Code score: ").append(q.getCodingSubmission().getScore()).append("/100\n");
                sb.append("Code evaluation: ").append(q.getCodingSubmission().getAiEvaluation()).append("\n");
            } else {
                sb.append("Answer: ").append(q.getAnswer() == null || q.getAnswer().isBlank()
                        ? "(no answer)" : q.getAnswer()).append("\n");
            }
            if (q.getFeedback() != null && !q.getFeedback().isBlank()) {
                sb.append("Live feedback: ").append(q.getFeedback()).append("\n");
            }
            sb.append("\n");
        }

        String prompt = sb + """

                Return ONLY valid JSON with this structure:
                {
                  "overallAnalysis": "3-4 sentences about this exact interview.",
                  "communicationAnalysis": "2-3 sentences about clarity, structure, confidence, and answer quality.",
                  "answeringFlowAnalysis": "2-3 sentences about whether answers were direct, complete, and well organized.",
                  "strengthsSummary": "Specific strengths shown in this interview.",
                  "improvementPlan": "3 concrete next steps for the next interview.",
                  "interviewerVerdict": "A concise interviewer-style verdict for this session."
                }
                """;

        try {
            Map<String, Object> raw = geminiService.parseJsonObjectOrThrow(geminiService.callGeminiWithTemp(
                    prompt,
                    "You are Sarah, a senior interviewer for the requested role. Analyze only this one session. Be specific, fair, and actionable. Return only JSON.",
                    0.35, uid, interviewId, "single_interview_analysis"));
            int score = interview.getScores() != null ? interview.getScores().getOverall() : 0;
            Interview.Analysis analysis = Interview.Analysis.builder()
                    .overallAnalysis(String.valueOf(raw.getOrDefault("overallAnalysis", "")))
                    .communicationAnalysis(String.valueOf(raw.getOrDefault("communicationAnalysis", "")))
                    .answeringFlowAnalysis(String.valueOf(raw.getOrDefault("answeringFlowAnalysis", "")))
                    .strengthsSummary(String.valueOf(raw.getOrDefault("strengthsSummary", "")))
                    .improvementPlan(String.valueOf(raw.getOrDefault("improvementPlan", "")))
                    .interviewerVerdict(String.valueOf(raw.getOrDefault("interviewerVerdict", "")))
                    .generatedAt(System.currentTimeMillis())
                    .build();
            interviewRepository.updateAnalysis(interviewId, analysis);
            return toAnalysisResponse(analysis, score);
        } catch (Exception e) {
            log.error("Single interview analysis failed: {}", e.getMessage());
            return buildSingleInterviewFallback(interview, "AI analysis is temporarily unavailable. Please try again.");
        }
    }

    // ── Helpers ──
    private Dto.PerformanceAnalysisResponse buildSingleInterviewFallback(Interview interview, String message) {
        int score = interview.getScores() != null ? interview.getScores().getOverall() : 0;
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis(message)
                .communicationAnalysis("Review the Q&A tab to inspect your answer clarity and completeness.")
                .answeringFlowAnalysis("Try to answer with a direct point first, then one short example, then tradeoffs.")
                .strengthsSummary("Strengths require completed answer data for this session.")
                .improvementPlan("1. Give a direct answer first.\n2. Add one concrete project example.\n3. Close with tradeoffs or impact.")
                .interviewerVerdict("Session analysis could not be generated right now.")
                .categoryInsights(List.of())
                .sessionCount(1)
                .avgScore(score)
                .bestScore(score)
                .trend("neutral")
                .build();
    }

    private Dto.PerformanceAnalysisResponse toAnalysisResponse(Interview.Analysis analysis, int score) {
        return Dto.PerformanceAnalysisResponse.builder()
                .overallAnalysis(analysis.getOverallAnalysis())
                .communicationAnalysis(analysis.getCommunicationAnalysis())
                .answeringFlowAnalysis(analysis.getAnsweringFlowAnalysis())
                .strengthsSummary(analysis.getStrengthsSummary())
                .improvementPlan(analysis.getImprovementPlan())
                .interviewerVerdict(analysis.getInterviewerVerdict())
                .categoryInsights(List.of())
                .sessionCount(1)
                .avgScore(score)
                .bestScore(score)
                .trend("neutral")
                .build();
    }

    private Dto.CodingSubmissionDetail toCodingSubmissionDetail(Interview.CodingSubmission codingSubmission) {
        if (codingSubmission == null) return null;
        return Dto.CodingSubmissionDetail.builder()
                .language(codingSubmission.getLanguage())
                .code(codingSubmission.getCode())
                .executionResult(codingSubmission.getExecutionResult())
                .aiEvaluation(codingSubmission.getAiEvaluation())
                .score(codingSubmission.getScore())
                .timeTakenMs(codingSubmission.getTimeTakenMs())
                .build();
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private int toInt(Object val, int fallback) {
        if (val == null) return fallback;
        try { return ((Number) val).intValue(); }
        catch (Exception e) { return fallback; }
    }

    private int totalCredits(User user) {
        if (user == null) return 0;
        int purchased = Math.max(0, user.getPurchasedCredits());
        int bonus = Math.max(0, user.getBonusCredits());
        if (purchased == 0 && bonus == 0 && user.getWalletCredits() > 0) {
            return user.getWalletCredits();
        }
        return purchased + bonus;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> convertCategoryScores(Map<String, Object> raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            result.put(e.getKey(), toInt(e.getValue(), 0));
        }
        return result;
    }

    private Dto.ScoresDto toScoresDto(Interview.Scores s) {
        if (s == null) return Dto.ScoresDto.builder().overall(0).build();
        return Dto.ScoresDto.builder()
                .overall(s.getOverall())
                .technical(s.getTechnical())
                .communication(s.getCommunication())
                .problemSolving(s.getProblemSolving())
                .roleDepth(s.getRoleDepth())
                .categories(s.getCategories())
                .build();
    }
}

