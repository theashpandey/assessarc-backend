package com.javadrill.service;

import com.javadrill.config.AppProperties;
import com.javadrill.dto.Dto;
import com.javadrill.dto.Dto.QADetailDto;
import com.javadrill.model.Interview;
import com.javadrill.model.QuestionBank;
import com.javadrill.model.User;
import com.javadrill.repository.InterviewRepository;
import com.javadrill.repository.QuestionBankRepository;
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

    private static final List<String> CATEGORIES = List.of(
            "java_core", "oops", "multithreading", "spring",
            "system_design", "problem_solving", "behavioral"
    );
    private static final int TARGET_QUESTION_COUNT = 10;
    private static final int TOP_UP_QUESTION_COUNT = 4;
    private static final int MIN_COMMON_BANK_SIZE = 24;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final InterviewRepository interviewRepository;
    private final QuestionBankRepository questionBankRepository;
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
        if (resumeSummary == null || resumeSummary.isBlank()) {
            log.info("Parsing resume for user {}", uid);
            resumeSummary = geminiService.parseResume(user.getResumeText());
            userRepository.updateResumeSummary(uid, resumeSummary);
        }

        // Build question list with smart dedup
        List<String> historicalQuestions = collectHistoricalQuestionTexts(uid, null);
        log.info("Building question set. User has {} historical questions.", historicalQuestions.size());

        List<Dto.QuestionDto> questions = buildQuestions(resumeSummary, historicalQuestions, interviewRole, experienceLevel);
        if (questions.size() < 2) {
            throw new RuntimeException("Could not generate enough questions. Please try again.");
        }

        // Persist interview session
        List<Interview.QuestionAnswer> qas = questions.stream()
                .map(q -> Interview.QuestionAnswer.builder()
                        .questionId(q.getId())
                        .question(q.getQuestion())
                        .category(q.getCategory())
                        .difficulty(q.getDifficulty())
                        .fromBank(q.isFromBank())
                        .answer("")
                        .feedback("")
                        .build())
                .collect(Collectors.toList());

        Interview interview = Interview.builder()
                .userId(uid)
                .status("STARTED")
                .durationMinutes(durationMinutes)
                .interviewRole(interviewRole)
                .experienceLevel(experienceLevel)
                .creditsDeducted(price)
                .startedAt(System.currentTimeMillis())
                .resumeSummary(resumeSummary)
                .questions(qas)
                .build();

        int newBalance = interviewRepository.saveStartedWithWalletDebit(interview, uid, price);
        log.info("Interview {} started for user {}", interview.getId(), uid);

        return Dto.StartInterviewResponse.builder()
                .interviewId(interview.getId())
                .resumeSummary(resumeSummary)
                .interviewRole(interviewRole)
                .experienceLevel(experienceLevel)
                .questions(questions)
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
                                                 String interviewRole, String experienceLevel) {
        List<String> allowedCategories = geminiService.categoriesForRole(interviewRole);
        if (false && "java_developer".equals(interviewRole)) {
            ensureCommonQuestionBank();
        }

        long bankTotal = 0;
        // How many to pick from bank vs generate fresh
        int bankPickCount = 0;
        int generateCount = Math.max(4, TARGET_QUESTION_COUNT - bankPickCount);

        Set<String> sessionExcludeIds = new HashSet<>();
        List<Dto.QuestionDto> result = new ArrayList<>();

        // Pick from bank — spread across categories
        List<String> shuffledCats = new ArrayList<>(allowedCategories);
        Collections.shuffle(shuffledCats);

        for (String cat : shuffledCats) {
            if (result.size() >= bankPickCount) break;
            List<QuestionBank> picked = questionBankRepository.pickRandom(cat, sessionExcludeIds, 3);
            Optional<QuestionBank> commonPick = picked.stream()
                    .filter(q -> !looksUserSpecific(q.getText()))
                    .findFirst();
            if (commonPick.isPresent()) {
                QuestionBank q = commonPick.get();
                sessionExcludeIds.add(q.getId());
                result.add(Dto.QuestionDto.builder()
                        .id(q.getId())
                        .question(q.getText())
                        .category(q.getCategory())
                        .difficulty(q.getDifficulty())
                        .fromBank(true)
                        .build());
            }
        }

        log.info("Picked {} from bank. Generating {} fresh questions.", result.size(), generateCount);

        // Generate fresh questions — avoid already picked ones
        List<Map<String, String>> newQs = geminiService.generateQuestions(
                resumeSummary, existingQuestionTexts, generateCount, interviewRole, experienceLevel, allowedCategories);

        for (var qMap : newQs) {
            String text = qMap.get("question");
            String cat  = qMap.get("category");
            String diff = qMap.getOrDefault("difficulty", "medium");
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;

            result.add(Dto.QuestionDto.builder()
                    .id("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .fromBank(false)
                    .build());
        }

        if (result.isEmpty()) {
            throw new RuntimeException("Could not generate questions. Please try again.");
        }

        // Increment usage count for bank questions
        result.stream()
              .filter(Dto.QuestionDto::isFromBank)
              .forEach(q -> questionBankRepository.incrementUsedCount(q.getId()));

        // Shuffle so questions don't always come in same category order
        Collections.shuffle(result);
        log.info("Final question set: {} questions", result.size());
        return result;
    }

    private void ensureCommonQuestionBank() {
        long bankTotal = questionBankRepository.countAll();
        if (bankTotal >= MIN_COMMON_BANK_SIZE) return;

        List<String> existingTexts = questionBankRepository.findAll().stream()
                .map(QuestionBank::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        int missing = (int) Math.min(12, MIN_COMMON_BANK_SIZE - bankTotal);
        List<Map<String, String>> commonQs = geminiService.generateCommonQuestions(existingTexts, missing);

        int added = 0;
        for (var qMap : commonQs) {
            String text = qMap.get("question");
            String cat = qMap.get("category");
            String diff = qMap.getOrDefault("difficulty", "medium");
            if (text == null || text.isBlank() || cat == null || !CATEGORIES.contains(cat)) continue;
            if (looksUserSpecific(text)) continue;
            if (questionBankRepository.addIfNotDuplicate(text, cat, diff).isPresent()) {
                added++;
            }
        }
        if (added > 0) {
            log.info("Seeded {} common questions into shared question bank", added);
        }
    }

    private boolean looksUserSpecific(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("your resume")
                || lower.contains("your profile")
                || lower.contains("your background")
                || lower.contains("your current project")
                || lower.contains("your recent project")
                || lower.contains("you worked on")
                || lower.contains("based on your")
                || lower.contains("from your experience");
    }

    // ── Submit Answer ──
    public Dto.NextQuestionResponse nextQuestion(String uid, Dto.NextQuestionRequest req) {
        Interview interview = interviewRepository.findById(req.getInterviewId())
                .orElseThrow(() -> new RuntimeException("Interview not found"));
        if (!interview.getUserId().equals(uid)) throw new RuntimeException("Unauthorized");
        if (!"STARTED".equals(interview.getStatus())) {
            throw new RuntimeException("Interview is not in progress");
        }

        List<Interview.QuestionAnswer> existing = interview.getQuestions() != null
                ? interview.getQuestions() : List.of();
        List<String> existingTexts = new ArrayList<>(collectHistoricalQuestionTexts(uid, interview.getId()));
        existing.stream()
                .map(Interview.QuestionAnswer::getQuestion)
                .filter(Objects::nonNull)
                .forEach(existingTexts::add);

        List<String> allowedCategories = geminiService.categoriesForRole(interview.getInterviewRole());
        List<Map<String, String>> generated = geminiService.generateQuestions(
                interview.getResumeSummary(), existingTexts, TOP_UP_QUESTION_COUNT,
                interview.getInterviewRole(), interview.getExperienceLevel(), allowedCategories);

        Dto.QuestionDto next = null;
        for (Map<String, String> qMap : generated) {
            String text = qMap.get("question");
            String cat = qMap.get("category");
            String diff = qMap.getOrDefault("difficulty", "medium");
            if (text == null || text.isBlank() || cat == null || !allowedCategories.contains(cat)) continue;
            next = Dto.QuestionDto.builder()
                    .id("ai_" + UUID.randomUUID())
                    .question(text)
                    .category(cat)
                    .difficulty(diff)
                    .fromBank(false)
                    .build();
            break;
        }
        if (next == null) throw new RuntimeException("Could not generate the next question. Please try again.");

        Interview.QuestionAnswer qa = Interview.QuestionAnswer.builder()
                .questionId(next.getId())
                .question(next.getQuestion())
                .category(next.getCategory())
                .difficulty(next.getDifficulty())
                .fromBank(false)
                .answer("")
                .feedback("")
                .build();
        interviewRepository.appendQuestion(interview.getId(), qa);

        return Dto.NextQuestionResponse.builder()
                .question(next)
                .questionIndex(existing.size())
                .build();
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
                    interview.getInterviewRole(), interview.getExperienceLevel());
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
                    .build();
        }

        List<Interview.QuestionAnswer> qas = interview.getQuestions();
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
        List<String> allowedCategories = geminiService.categoriesForRole(interview.getInterviewRole());
        Map<String, Object> rawScores = geminiService.calculateScores(
                qaList, interview.getInterviewRole(), interview.getExperienceLevel(), allowedCategories);

        @SuppressWarnings("unchecked")
        Map<String, Integer> categories = convertCategoryScores(
                (Map<String, Object>) rawScores.getOrDefault("categories", Map.of()));

        Interview.Scores scores = Interview.Scores.builder()
                .overall(toInt(rawScores.get("overall"), 70))
                .technical(toInt(rawScores.get("technical"), 70))
                .communication(toInt(rawScores.get("communication"), 70))
                .problemSolving(toInt(rawScores.get("problemSolving"), 70))
                .javaDepth(toInt(rawScores.get("javaDepth"), 70))
                .categories(categories)
                .build();

        long completedAt = System.currentTimeMillis();

        // Track which bank question IDs were used
        List<String> bankIds = qas.stream()
                .filter(Interview.QuestionAnswer::isFromBank)
                .map(Interview.QuestionAnswer::getQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        interviewRepository.completeInterview(interviewId, scores, completedAt, bankIds);

        // Update user's seen question IDs (so next session avoids them)
        // Only track bank questions — AI-generated are already fresh
        if (!bankIds.isEmpty()) {
            userRepository.addSeenQuestionIds(uid, bankIds);
        }

        // Update user stats
        List<Interview> allCompleted = interviewRepository.findAllCompletedByUserId(uid);
        userRepository.updateStats(uid, scores.getOverall(), allCompleted.size());

        log.info("Interview {} completed. Score: {}%", interviewId, scores.getOverall());

        return Dto.CompleteInterviewResponse.builder()
                .interviewId(interviewId)
                .completedAt(completedAt)
                .scores(toScoresDto(scores))
                .build();
    }

    // ── History ──
    public List<Dto.InterviewHistoryItem> getHistory(String uid) {
        List<Interview> interviews = interviewRepository.findByUserId(uid, 10);
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
            Map<String, Object> raw = geminiService.safeParseJsonObject(geminiService.callGeminiWithTemp(
                    prompt,
                    "You are Sarah, a senior interviewer for the requested role. Analyze only this one session. Be specific, fair, and actionable. Return only JSON.",
                    0.35));
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
                .javaDepth(s.getJavaDepth())
                .categories(s.getCategories())
                .build();
    }
}
