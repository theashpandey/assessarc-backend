package com.assessarc.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.assessarc.model.Interview;
import com.assessarc.model.User;
import com.assessarc.model.WalletTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InterviewRepository {

    private static final String COLLECTION = "interviews";
    private static final String QUESTIONS_SUBCOLLECTION = "questions";
    private static final String ANSWERS_SUBCOLLECTION = "answers";
    private static final String USERS_COLLECTION = "users";
    private static final String WALLET_TRANSACTIONS_COLLECTION = "wallet_transactions";
    private final Firestore firestore;

    public Interview save(Interview interview) {
        try {
            if (interview.getId() == null) {
                interview.setId(UUID.randomUUID().toString());
            }
            firestore.collection(COLLECTION).document(interview.getId()).set(interview).get();
            log.info("Saved interview {} for user {}", interview.getId(), interview.getUserId());
            return interview;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving interview: {}", e.getMessage());
            throw new RuntimeException("Failed to save interview", e);
        }
    }

    public Optional<Interview> findById(String id) {
        try {
            var doc = firestore.collection(COLLECTION).document(id).get().get();
            if (!doc.exists()) {
                log.warn("Interview {} not found", id);
                return Optional.empty();
            }
            Interview iv = doc.toObject(Interview.class);
            if (iv != null) iv.setId(doc.getId());
            hydrateQuestionAnswers(id, iv);
            return Optional.ofNullable(iv);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching interview {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Interview> findAll() {
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                    .orderBy("startedAt", Query.Direction.DESCENDING)
                    .get().get().getDocuments();
            return docs.stream()
                    .map(d -> {
                        Interview iv = d.toObject(Interview.class);
                        if (iv != null) {
                            iv.setId(d.getId());
                            hydrateQuestionAnswers(d.getId(), iv);
                        }
                        return iv;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch interviews", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch interviews", e);
        }
    }

    public List<Interview> findByUserId(String userId, int limit) {
        return fetchAndFilter(userId, limit);
    }

    public List<Interview> findAllCompletedByUserId(String userId) {
        return fetchAndFilter(userId, Integer.MAX_VALUE);
    }

    public List<Interview> findRecentCompletedByUserId(String userId, int limit) {
        return fetchAndFilter(userId, limit);
    }

    public List<Interview> findReportableByUserId(String userId, int limit) {
        return fetchByStatuses(userId, Set.of("COMPLETED", "ANALYSIS_PENDING"), limit);
    }

    private List<Interview> fetchAndFilter(String userId, int limit) {
        return fetchByStatuses(userId, Set.of("COMPLETED"), limit);
    }

    private List<Interview> fetchByStatuses(String userId, Set<String> statuses, int limit) {
        try {
            Query query = firestore.collection(COLLECTION)
                    .whereEqualTo("userId", userId)
                    .whereIn("status", new ArrayList<>(statuses))
                    .orderBy("completedAt", Query.Direction.DESCENDING);
            if (limit != Integer.MAX_VALUE) {
                query = query.limit(limit);
            }

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<Interview> result = docs.stream()
                    .map(d -> {
                        try {
                        Interview iv = d.toObject(Interview.class);
                        if (iv != null) {
                            iv.setId(d.getId());
                            hydrateQuestionAnswers(d.getId(), iv);
                        }
                        return iv;
                    } catch (Exception e) {
                            log.error("Failed to deserialize interview doc {}: {}", d.getId(), e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.debug("Returning {} reportable interviews for userId={}", result.size(), userId);
            return result;

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching interviews for user {}: {}", userId, e.getMessage(), e);
            return List.of();
        }
    }

    public int saveStartedWithWalletDebit(Interview interview, String uid, int price) {
        try {
            if (interview.getId() == null || interview.getId().isBlank()) {
                interview.setId(UUID.randomUUID().toString());
            }
            String txId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            var userRef = firestore.collection(USERS_COLLECTION).document(uid);
            var interviewRef = firestore.collection(COLLECTION).document(interview.getId());
            var txRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION).document(txId);

            int newBalance = firestore.runTransaction(transaction -> {
                var userSnap = transaction.get(userRef).get();
                if (!userSnap.exists()) {
                    throw new RuntimeException("User not found");
                }

                User user = userSnap.toObject(User.class);
                int purchasedBefore = user != null ? Math.max(0, user.getPurchasedCredits()) : 0;
                int bonusBefore = user != null ? Math.max(0, user.getBonusCredits()) : 0;
                if (purchasedBefore == 0 && bonusBefore == 0 && user != null && user.getWalletCredits() > 0) {
                    purchasedBefore = user.getWalletCredits();
                }
                int balanceBefore = purchasedBefore + bonusBefore;
                if (balanceBefore < price) {
                    throw new RuntimeException("Insufficient credits. Need " + price
                            + " credits, you have " + balanceBefore + ".");
                }

                int purchasedDebit = Math.min(purchasedBefore, price);
                int remaining = price - purchasedDebit;
                int bonusDebit = Math.min(bonusBefore, remaining);
                int purchasedAfter = purchasedBefore - purchasedDebit;
                int bonusAfter = bonusBefore - bonusDebit;
                int balanceAfter = purchasedAfter + bonusAfter;
                WalletTransaction walletTx = WalletTransaction.builder()
                        .id(txId)
                        .uid(uid)
                        .type("INTERVIEW_DEBIT")
                        .amount(price)
                        .balanceBefore(balanceBefore)
                        .balanceAfter(balanceAfter)
                        .purchasedBefore(purchasedBefore)
                        .purchasedAfter(purchasedAfter)
                        .bonusBefore(bonusBefore)
                        .bonusAfter(bonusAfter)
                        .purchasedDelta(-purchasedDebit)
                        .bonusDelta(-bonusDebit)
                        .description(interview.getDurationMinutes() + " min interview session")
                        .interviewId(interview.getId())
                        .createdAt(now)
                        .build();

                List<Interview.QuestionAnswer> initialQuestions = interview.getQuestions() != null
                        ? new ArrayList<>(interview.getQuestions()) : new ArrayList<>();
                Interview storedInterview = Interview.builder()
                        .id(interview.getId())
                        .userId(interview.getUserId())
                        .status(interview.getStatus())
                        .durationMinutes(interview.getDurationMinutes())
                        .interviewRole(interview.getInterviewRole())
                        .experienceLevel(interview.getExperienceLevel())
                        .creditsDeducted(interview.getCreditsDeducted())
                        .startedAt(interview.getStartedAt())
                        .resumeSummary(interview.getResumeSummary())
                        .questions(List.of())
                        .questionPool(List.of())
                        .askedQuestionCount(initialQuestions.size())
                        .build();

                transaction.update(userRef,
                        "purchasedCredits", purchasedAfter,
                        "bonusCredits", bonusAfter,
                        "walletCredits", balanceAfter);
                transaction.set(interviewRef, storedInterview);
                writeQuestionDocs(transaction, interviewRef, initialQuestions, 0);
                transaction.set(txRef, walletTx);
                return balanceAfter;
            }).get();

            log.info("Saved interview {} and debited {} credits for user {}", interview.getId(), price, uid);
            return newBalance;
        } catch (InterruptedException | ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            log.error("Error starting interview transaction: {}", e.getMessage());
            throw new RuntimeException("Failed to start interview", e);
        }
    }

    public void updateAnswerAndFeedback(String interviewId, int questionIndex,
                                         String answer, String feedback) {
        updateAnswerAndFeedback(interviewId, questionIndex, answer, feedback, null);
    }

    public void updateAnswerAndFeedback(String interviewId, int questionIndex,
                                         String answer, String feedback,
                                         Interview.AnswerTrace answerTrace) {
        try {
            var docRef = firestore.collection(COLLECTION).document(interviewId);
            firestore.runTransaction(tx -> {
                var snap = tx.get(docRef).get();
                if (!snap.exists()) throw new RuntimeException("Interview not found: " + interviewId);

                Interview iv = snap.toObject(Interview.class);
                if (iv == null) {
                    throw new RuntimeException("Invalid question index: " + questionIndex);
                }

                long now = System.currentTimeMillis();
                Interview.QuestionAnswer qa = readQuestionInTransaction(tx, docRef, iv, questionIndex);
                qa.setAnswer(answer);
                qa.setFeedback(feedback);
                qa.setAnswerTimestamp(now);
                if (answerTrace != null) {
                    qa.setAnswerTrace(answerTrace);
                }

                tx.set(docRef.collection(ANSWERS_SUBCOLLECTION).document(String.valueOf(questionIndex)), qa);
                tx.update(docRef, "lastAnswerUpdatedAt", now);
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating Q&A for interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to save answer", e);
        }
    }

    public void updateCodingSubmission(String interviewId, int questionIndex,
                                        Interview.CodingSubmission codingSubmission) {
        try {
            var docRef = firestore.collection(COLLECTION).document(interviewId);
            firestore.runTransaction(tx -> {
                var snap = tx.get(docRef).get();
                if (!snap.exists()) throw new RuntimeException("Interview not found: " + interviewId);

                Interview iv = snap.toObject(Interview.class);
                if (iv == null) {
                    throw new RuntimeException("Invalid question index: " + questionIndex);
                }

                long now = System.currentTimeMillis();
                Interview.QuestionAnswer qa = readQuestionInTransaction(tx, docRef, iv, questionIndex);
                qa.setCodingSubmission(codingSubmission);
                qa.setAnswer(codingSubmission.getCode());
                qa.setFeedback(codingSubmission.getAiEvaluation());
                qa.setAnswerTimestamp(now);

                tx.set(docRef.collection(ANSWERS_SUBCOLLECTION).document(String.valueOf(questionIndex)), qa);
                tx.update(docRef, "lastAnswerUpdatedAt", now);
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating coding submission for interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to save coding submission", e);
        }
    }

    public int moveNextPooledQuestionToAsked(String interviewId) {
        try {
            var docRef = firestore.collection(COLLECTION).document(interviewId);
            return firestore.runTransaction(tx -> {
                var snap = tx.get(docRef).get();
                if (!snap.exists()) throw new RuntimeException("Interview not found: " + interviewId);

                Interview iv = snap.toObject(Interview.class);
                if (iv == null) throw new RuntimeException("Interview not found: " + interviewId);
                List<Interview.QuestionAnswer> pool = iv.getQuestionPool() != null
                        ? new ArrayList<>(iv.getQuestionPool()) : new ArrayList<>();
                if (pool.isEmpty()) return -1;

                int nextIndex = Math.max(0, iv.getAskedQuestionCount());
                Interview.QuestionAnswer nextQuestion = pool.remove(0);

                tx.update(docRef,
                        "questionPool", pool,
                        "askedQuestionCount", nextIndex + 1);
                tx.set(docRef.collection(QUESTIONS_SUBCOLLECTION).document(String.valueOf(nextIndex)), nextQuestion);
                return nextIndex;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error moving pooled question for interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to prepare next question", e);
        }
    }

    public void appendQuestionsToPool(String interviewId, List<Interview.QuestionAnswer> newQuestions) {
        try {
            var docRef = firestore.collection(COLLECTION).document(interviewId);
            firestore.runTransaction(tx -> {
                var snap = tx.get(docRef).get();
                if (!snap.exists()) throw new RuntimeException("Interview not found: " + interviewId);

                Interview iv = snap.toObject(Interview.class);
                if (iv == null) throw new RuntimeException("Interview not found: " + interviewId);
                List<Interview.QuestionAnswer> pool = iv.getQuestionPool() != null
                        ? new ArrayList<>(iv.getQuestionPool()) : new ArrayList<>();
                pool.addAll(newQuestions);
                tx.update(docRef, "questionPool", pool);
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error appending pooled questions for interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to prepare question pool", e);
        }
    }

    public void completeInterview(String interviewId, Interview.Scores scores, long completedAt) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "COMPLETED");
            updates.put("scores", scores);
            updates.put("completedAt", completedAt);
            updates.put("questionPool", List.of());
            updates.put("completionMessage", null);
            updates.put("analysisRetryAfter", 0);
            firestore.collection(COLLECTION).document(interviewId).update(updates).get();
            log.info("Interview {} marked COMPLETED with score={}", interviewId,
                    scores != null ? scores.getOverall() : "?");
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error completing interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to complete interview", e);
        }
    }

    public void markAnalysisPending(String interviewId, List<Interview.QuestionAnswer> askedQuestions,
                                    long completedAt, String message) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "ANALYSIS_PENDING");
            updates.put("questionPool", List.of());
            updates.put("scores", null);
            updates.put("completedAt", completedAt);
            updates.put("completionMessage", message);
            updates.put("analysisRetryAfter", System.currentTimeMillis() + 15 * 60 * 1000);
            firestore.collection(COLLECTION).document(interviewId).update(updates).get();
            log.info("Interview {} marked ANALYSIS_PENDING", interviewId);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error marking interview {} pending analysis: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to save interview state", e);
        }
    }

    public void updateAnalysis(String interviewId, Interview.Analysis analysis) {
        try {
            firestore.collection(COLLECTION).document(interviewId)
                    .update("analysis", analysis).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving analysis for interview {}: {}", interviewId, e.getMessage());
            throw new RuntimeException("Failed to save interview analysis", e);
        }
    }

    public void updateResumeSummary(String interviewId, String resumeSummary) {
        try {
            firestore.collection(COLLECTION).document(interviewId)
                    .update("resumeSummary", resumeSummary).get();
        } catch (InterruptedException | ExecutionException e) {
            log.warn("Could not update resume summary for interview {}: {}", interviewId, e.getMessage());
        }
    }

    private void hydrateQuestionAnswers(String interviewId, Interview interview) {
        if (interview == null) return;
        try {
            var docRef = firestore.collection(COLLECTION).document(interviewId);
            List<Interview.QuestionAnswer> questions = interview.getQuestions() != null
                    ? new ArrayList<>(interview.getQuestions()) : new ArrayList<>();

            List<QueryDocumentSnapshot> questionDocs = docRef.collection(QUESTIONS_SUBCOLLECTION).get().get().getDocuments();
            for (QueryDocumentSnapshot doc : questionDocs) {
                int index = parseIndex(doc.getId());
                if (index < 0) continue;
                ensureSize(questions, index + 1);
                Interview.QuestionAnswer question = doc.toObject(Interview.QuestionAnswer.class);
                if (question != null) {
                    Interview.QuestionAnswer existing = questions.get(index);
                    questions.set(index, mergeQuestionAndAnswer(question, existing));
                }
            }

            List<QueryDocumentSnapshot> answerDocs = docRef.collection(ANSWERS_SUBCOLLECTION).get().get().getDocuments();
            for (QueryDocumentSnapshot doc : answerDocs) {
                int index = parseIndex(doc.getId());
                if (index < 0) continue;
                ensureSize(questions, index + 1);
                Interview.QuestionAnswer answer = doc.toObject(Interview.QuestionAnswer.class);
                if (answer != null) {
                    Interview.QuestionAnswer existing = questions.get(index);
                    questions.set(index, mergeQuestionAndAnswer(existing, answer));
                }
            }

            interview.setQuestions(questions.stream().filter(Objects::nonNull).collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("Could not hydrate interview subcollections for {}: {}", interviewId, e.getMessage());
        }
    }

    private void writeQuestionDocs(com.google.cloud.firestore.Transaction transaction,
                                   com.google.cloud.firestore.DocumentReference interviewRef,
                                   List<Interview.QuestionAnswer> questions,
                                   int startIndex) {
        if (questions == null || questions.isEmpty()) return;
        for (int i = 0; i < questions.size(); i++) {
            transaction.set(interviewRef.collection(QUESTIONS_SUBCOLLECTION)
                    .document(String.valueOf(startIndex + i)), questions.get(i));
        }
    }

    private Interview.QuestionAnswer readQuestionInTransaction(com.google.cloud.firestore.Transaction tx,
                                                               com.google.cloud.firestore.DocumentReference interviewRef,
                                                               Interview interview,
                                                               int questionIndex) throws ExecutionException, InterruptedException {
        if (questionIndex < 0) {
            throw new RuntimeException("Invalid question index: " + questionIndex);
        }

        var questionRef = interviewRef.collection(QUESTIONS_SUBCOLLECTION).document(String.valueOf(questionIndex));
        var questionSnap = tx.get(questionRef).get();
        if (questionSnap.exists()) {
            Interview.QuestionAnswer question = questionSnap.toObject(Interview.QuestionAnswer.class);
            if (question != null) return question;
        }

        if (interview.getQuestions() != null && questionIndex < interview.getQuestions().size()) {
            Interview.QuestionAnswer question = interview.getQuestions().get(questionIndex);
            if (question != null) return question;
        }

        throw new RuntimeException("Invalid question index: " + questionIndex);
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private void ensureSize(List<Interview.QuestionAnswer> questions, int size) {
        while (questions.size() < size) {
            questions.add(null);
        }
    }

    private Interview.QuestionAnswer mergeQuestionAndAnswer(Interview.QuestionAnswer question,
                                                            Interview.QuestionAnswer answer) {
        if (question == null) return answer;
        if (answer == null) return question;
        if (answer.getAnswer() != null) question.setAnswer(answer.getAnswer());
        if (answer.getFeedback() != null) question.setFeedback(answer.getFeedback());
        if (answer.getAnswerTimestamp() > 0) question.setAnswerTimestamp(answer.getAnswerTimestamp());
        if (answer.getAnswerTrace() != null) question.setAnswerTrace(answer.getAnswerTrace());
        if (answer.getCodingSubmission() != null) question.setCodingSubmission(answer.getCodingSubmission());
        return question;
    }

}
