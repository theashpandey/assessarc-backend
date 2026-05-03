package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Firestore collection: "question_bank"
 * CENTRAL bank — shared across ALL users.
 * AI calls are minimized by picking from here first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBank {
    private String id;
    private String text;
    private String category;    // java_core | oops | multithreading | spring | system_design | problem_solving | behavioral
    private String difficulty;  // easy | medium | hard
    private int usedCount;      // how many times picked across all sessions
    private long addedAt;
    private String normalizedText; // lowercase trimmed — for dedup check
    private boolean isActive;   // can disable questions
}
