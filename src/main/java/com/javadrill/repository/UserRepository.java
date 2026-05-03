package com.javadrill.repository;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.javadrill.model.User;
import com.javadrill.model.WalletTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;

@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private static final String COLLECTION = "users";
    private static final String WALLET_TRANSACTIONS_COLLECTION = "wallet_transactions";
    private final Firestore firestore;

    public Optional<User> findById(String uid) {
        try {
            var doc = firestore.collection(COLLECTION).document(uid).get().get();
            if (!doc.exists()) return Optional.empty();
            return Optional.ofNullable(doc.toObject(User.class));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching user {}: {}", uid, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<User> findByReferralCode(String referralCode) {
        if (referralCode == null || referralCode.isBlank()) return Optional.empty();
        try {
            var docs = firestore.collection(COLLECTION)
                    .whereEqualTo("referralCode", referralCode.trim().toUpperCase(Locale.ROOT))
                    .limit(1)
                    .get().get()
                    .getDocuments();
            if (docs.isEmpty()) return Optional.empty();
            return Optional.ofNullable(docs.get(0).toObject(User.class));
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching referral code {}: {}", referralCode, e.getMessage());
            return Optional.empty();
        }
    }

    public User save(User user) {
        try {
            firestore.collection(COLLECTION).document(user.getUid()).set(user).get();
            return user;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving user {}: {}", user.getUid(), e.getMessage());
            throw new RuntimeException("Failed to save user", e);
        }
    }

    public User createUserWithReferralReward(User newUser, String referralCode, int rewardCredits) {
        String normalizedReferral = referralCode == null ? "" : referralCode.trim().toUpperCase(Locale.ROOT);
        User referrer = findByReferralCode(normalizedReferral).orElse(null);
        if (referrer != null && referrer.getUid().equals(newUser.getUid())) {
            referrer = null;
        }

        try {
            User finalReferrer = referrer;
            return firestore.runTransaction(transaction -> {
                var newUserRef = firestore.collection(COLLECTION).document(newUser.getUid());
                var existingNewUser = transaction.get(newUserRef).get();
                if (existingNewUser.exists()) {
                    User existing = existingNewUser.toObject(User.class);
                    return existing != null ? existing : newUser;
                }

                if (finalReferrer != null) {
                    var referrerRef = firestore.collection(COLLECTION).document(finalReferrer.getUid());
                    var referrerSnap = transaction.get(referrerRef).get();
                    var rewardTxRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION)
                            .document("referral_" + newUser.getUid());
                    var rewardTxSnap = transaction.get(rewardTxRef).get();

                    if (referrerSnap.exists() && !rewardTxSnap.exists()) {
                        User currentReferrer = referrerSnap.toObject(User.class);
                        int before = currentReferrer != null ? currentReferrer.getWalletCredits() : 0;
                        int after = before + rewardCredits;
                        WalletTransaction rewardTx = WalletTransaction.builder()
                                .id(rewardTxRef.getId())
                                .uid(finalReferrer.getUid())
                                .type("credit")
                                .amount(rewardCredits)
                                .balanceBefore(before)
                                .balanceAfter(after)
                                .description("Referral reward for inviting " + newUser.getEmail())
                                .createdAt(System.currentTimeMillis())
                                .build();

                        newUser.setReferredBy(finalReferrer.getReferralCode());
                        transaction.update(referrerRef, "walletCredits", after);
                        transaction.set(rewardTxRef, rewardTx);
                    }
                }

                transaction.set(newUserRef, newUser);
                return newUser;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error creating user {} with referral reward: {}", newUser.getUid(), e.getMessage());
            throw new RuntimeException("Failed to save user", e);
        }
    }

    public void updateWallet(String uid, int newBalance) {
        try {
            firestore.collection(COLLECTION).document(uid)
                    .update("walletCredits", newBalance).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating wallet for {}: {}", uid, e.getMessage());
            throw new RuntimeException("Failed to update wallet", e);
        }
    }

    public void updateResume(String uid, String resumeText, String fileName) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("resumeText", resumeText);
            updates.put("resumeFileName", fileName);
            updates.put("resumeUploadedAt", System.currentTimeMillis());
            updates.put("resumeSummary", null); // reset cached summary

            firestore.collection(COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating resume for {}: {}", uid, e.getMessage());
            throw new RuntimeException("Failed to update resume", e);
        }
    }

    public void updateResumeSummary(String uid, String summary) {
        try {
            firestore.collection(COLLECTION).document(uid)
                    .update("resumeSummary", summary).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating resume summary for {}: {}", uid, e.getMessage());
        }
    }

    public void updateLastActive(String uid) {
        try {
            firestore.collection(COLLECTION).document(uid)
                    .update("lastActiveAt", System.currentTimeMillis()).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating last active for {}", uid);
        }
    }

    /**
     * Append question IDs to the user's seenQuestionIds list.
     * Used to avoid repeating questions across multiple sessions.
     */
    public void addSeenQuestionIds(String uid, List<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return;
        try {
            firestore.collection(COLLECTION).document(uid)
                    .update("seenQuestionIds", FieldValue.arrayUnion(questionIds.toArray()))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error adding seen question IDs for {}: {}", uid, e.getMessage());
        }
    }

    /**
     * Update interview stats after completion
     */
    public void updateStats(String uid, int newScore, int totalInterviews) {
        try {
            var user = findById(uid);
            if (user.isEmpty()) return;
            User u = user.get();

            double newAvg = ((u.getAvgScore() * (totalInterviews - 1)) + newScore) / totalInterviews;
            int bestScore = Math.max(u.getBestScore(), newScore);

            Map<String, Object> updates = new HashMap<>();
            updates.put("totalInterviews", totalInterviews);
            updates.put("avgScore", Math.round(newAvg * 10.0) / 10.0);
            updates.put("bestScore", bestScore);

            firestore.collection(COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating stats for {}: {}", uid, e.getMessage());
        }
    }
}
