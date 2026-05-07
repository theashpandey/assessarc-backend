package com.javadrill.service;

import com.javadrill.config.AppProperties;
import com.javadrill.dto.Dto;
import com.javadrill.dto.Dto.QADetailDto;
import com.javadrill.model.Interview;
import com.javadrill.model.User;
import com.javadrill.repository.InterviewRepository;
import com.javadrill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        if (user.getWalletCredits() < price) {
            throw new RuntimeException("Insufficient credits. Need " + price
                    + " credits, you have " + user.getWalletCredits() + ".");
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
                uid, interviewId, "initial_question_generation");
        if (questions.size() < 2) {
            throw new RuntimeException("Could not generate enough questions. Please try again.");
        }

        List<Interview.QuestionAnswer> generatedQuestions = questions.stream()
                .map(q -> Interview.QuestionAnswer.builder()
                        .questionId(q.getId())
                        .question(q.getQuestion())
                        .category(q.getCategory())
                        .difficulty(q.getDifficulty())
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
                                                 List<String> resumeCategories,
                                                 String uid, String interviewId, String callType) {
        List<String> allowedCategories = geminiService.categoriesForInterview(interviewRole, resumeCategories);
        List<Dto.QuestionDto> result = new ArrayList<>();
        Set<String> seenQuestionTexts = existingQuestionTexts.stream()
                .map(this::normalizeQuestionText)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toCollection(HashSet::new));

        log.info("Generating {} fresh questions.", TARGET_QUESTION_COUNT);
        List<Map<String, String>> newQs = geminiService.generateQuestions(
                resumeSummary, existingQuestionTexts, TARGET_QUESTION_COUNT,
                interviewRole, experienceLevel, allowedCategories,
                uid, interviewId, callType);

        for (var qMap : newQs) {
            String text = qMap.get("question");
            String cat  = qMap.get("category");
            String diff = qMap.getOrDefault("difficulty", "medium");
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;
            String normalized = normalizeQuestionText(text);
            if (normalized.isBlank() || !seenQuestionTexts.add(normalized)) continue;

            result.add(Dto.QuestionDto.builder()
                    .id("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .build());
        }

        if (result.isEmpty()) {
            throw new RuntimeException("Could not generate questions. Please try again.");
        }

        Collections.shuffle(result);
        log.info("Final question set: {} questions", result.size());
        return result;
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
        List<Map<String, String>> generated = geminiService.generateQuestions(
                interview.getResumeSummary(), existingTexts, TARGET_QUESTION_COUNT,
                interview.getInterviewRole(), interview.getExperienceLevel(), allowedCategories,
                uid, interview.getId(), "next_question_generation");

        List<Interview.QuestionAnswer> freshPool = new ArrayList<>();
        Set<String> seen = existingTexts.stream()
                .map(this::normalizeQuestionText)
                .collect(Collectors.toCollection(HashSet::new));
        for (Map<String, String> qMap : generated) {
            String text = qMap.get("question");
            String cat = qMap.get("category");
            String diff = qMap.getOrDefault("difficulty", "medium");
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;
            String normalized = normalizeQuestionText(text);
            if (normalized.isBlank() || !seen.add(normalized)) continue;
            freshPool.add(Interview.QuestionAnswer.builder()
                    .questionId("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .answer("")
                    .feedback("")
                    .build());
        }
        if (freshPool.isEmpty()) throw new RuntimeException("Could not generate the next question. Please try again.");

        interviewRepository.appendQuestionsToPool(interview.getId(), freshPool);
        return nextQuestion(uid, req);
    }

    private List<String> collectHistoricalQuestionTexts(String uid, String excludeInterviewId) {
        return interviewRepository.findAllCompletedByUserId(uid).stream()
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
            interviewRepository.updateAnswerAndFeedback(req.getInterviewId(), idx, "(skipped)", skipFeedback);
            return Dto.SubmitAnswerResponse.builder()
                    .feedback(skipFeedback)
                    .isLastQuestion(idx == questions.size() - 1)
                    .build();
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
        interviewRepository.updateAnswerAndFeedback(req.getInterviewId(), idx, answer, feedback);

        return Dto.SubmitAnswerResponse.builder()
                .feedback(feedback)
                .isLastQuestion(idx == questions.size() - 1)
                .build();
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
                    m.put("answer", q.getAnswer() != null && !q.getAnswer().isBlank()
                            ? q.getAnswer() : "(no answer)");
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
                                .answer(q.getAnswer() != null ? q.getAnswer() : "")
                                .feedback(q.getFeedback() != null ? q.getFeedback() : "")
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
            sb.append("Answer: ").append(q.getAnswer() == null || q.getAnswer().isBlank()
                    ? "(no answer)" : q.getAnswer()).append("\n");
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

    private int toInt(Object val, int fallback) {
        if (val == null) return fallback;
        try { return ((Number) val).intValue(); }
        catch (Exception e) { return fallback; }
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

