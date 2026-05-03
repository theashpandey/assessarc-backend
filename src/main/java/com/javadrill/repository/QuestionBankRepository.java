package com.javadrill.repository;

import com.google.cloud.firestore.Firestore;
import com.javadrill.model.QuestionBank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class QuestionBankRepository {

    private static final String COLLECTION = "question_bank";
    private final Firestore firestore;

    public List<QuestionBank> findByCategory(String category) {
        try {
            var docs = firestore.collection(COLLECTION)
                    .whereEqualTo("category", category)
                    .whereEqualTo("isActive", true)
                    .get().get().getDocuments();
            return docs.stream()
                    .map(d -> {
                        QuestionBank q = d.toObject(QuestionBank.class);
                        if (q != null) q.setId(d.getId());
                        return q;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching questions for category {}: {}", category, e.getMessage());
            return List.of();
        }
    }

    public long countAll() {
        try {
            return firestore.collection(COLLECTION)
                    .whereEqualTo("isActive", true)
                    .get().get().size();
        } catch (InterruptedException | ExecutionException e) {
            return 0;
        }
    }

    /**
     * Add question to bank if not duplicate.
     * Uses normalized text comparison.
     */
    public Optional<QuestionBank> addIfNotDuplicate(String text, String category, String difficulty) {
        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        try {
            // Check for duplicate by normalized text + category
            var existing = firestore.collection(COLLECTION)
                    .whereEqualTo("normalizedText", normalized)
                    .limit(1).get().get().getDocuments();

            if (!existing.isEmpty()) {
                // Return existing question
                QuestionBank q = existing.get(0).toObject(QuestionBank.class);
                if (q != null) q.setId(existing.get(0).getId());
                return Optional.ofNullable(q);
            }

            String id = UUID.randomUUID().toString();
            var q = QuestionBank.builder()
                    .id(id)
                    .text(text)
                    .category(category)
                    .difficulty(difficulty != null ? difficulty : "medium")
                    .usedCount(0)
                    .addedAt(System.currentTimeMillis())
                    .normalizedText(normalized)
                    .isActive(true)
                    .build();

            firestore.collection(COLLECTION).document(id).set(q).get();
            log.debug("Added to question bank: [{}] {}", category, text.substring(0, Math.min(60, text.length())));
            return Optional.of(q);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error adding question to bank: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Pick random questions from a category, excluding IDs already seen by user
     * AND IDs already picked in current session build.
     */
    public List<QuestionBank> pickRandom(String category, Set<String> excludeIds, int count) {
        List<QuestionBank> all = findByCategory(category);
        var filtered = all.stream()
                .filter(q -> !excludeIds.contains(q.getId()))
                .collect(Collectors.toList());
        Collections.shuffle(filtered);
        return filtered.stream().limit(count).collect(Collectors.toList());
    }

    public void incrementUsedCount(String questionId) {
        try {
            var ref = firestore.collection(COLLECTION).document(questionId);
            firestore.runTransaction(tx -> {
                var snap = tx.get(ref).get();
                if (!snap.exists()) return null;
                long current = snap.getLong("usedCount") != null ? snap.getLong("usedCount") : 0;
                tx.update(ref, "usedCount", current + 1);
                return null;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error incrementing usedCount for {}: {}", questionId, e.getMessage());
        }
    }

    public List<QuestionBank> findAll() {
        try {
            var docs = firestore.collection(COLLECTION)
                    .whereEqualTo("isActive", true)
                    .get().get().getDocuments();
            return docs.stream()
                    .map(d -> {
                        QuestionBank q = d.toObject(QuestionBank.class);
                        if (q != null) q.setId(d.getId());
                        return q;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all questions: {}", e.getMessage());
            return List.of();
        }
    }
}
