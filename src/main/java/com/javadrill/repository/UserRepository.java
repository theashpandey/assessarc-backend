package com.javadrill.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
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

    public List<User> findAll() {
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().get().getDocuments();
            return docs.stream()
                    .map(doc -> {
                        User user = doc.toObject(User.class);
                        if (user != null && (user.getUid() == null || user.getUid().isBlank())) {
                            user.setUid(doc.getId());
                        }
                        return user;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch users", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch users", e);
        }
    }

    public Optional<User> findByReferralCode(String referralCode) {
        if (referralCode == null || referralCode.isBlank()) return Optional.empty();
        String trimmed = referralCode.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        try {
            var query = firestore.collection(COLLECTION)
                    .whereEqualTo("referralCode", normalized)
                    .limit(1)
                    .get().get();
            if (!query.getDocuments().isEmpty()) {
                return Optional.ofNullable(query.getDocuments().get(0).toObject(User.class));
            }
            if (!trimmed.equals(normalized)) {
                var legacyQuery = firestore.collection(COLLECTION)
                        .whereEqualTo("referralCode", trimmed)
                        .limit(1)
                        .get().get();
                if (!legacyQuery.getDocuments().isEmpty()) {
                    return Optional.ofNullable(legacyQuery.getDocuments().get(0).toObject(User.class));
                }
            }
            return Optional.empty();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching referral code {}: {}", referralCode, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        try {
            var query = firestore.collection(COLLECTION)
                    .whereEqualTo("email", email.trim().toLowerCase(Locale.ROOT))
                    .limit(1)
                    .get().get();
            if (!query.getDocuments().isEmpty()) {
                User user = query.getDocuments().get(0).toObject(User.class);
                if (user != null && (user.getUid() == null || user.getUid().isBlank())) {
                    user.setUid(query.getDocuments().get(0).getId());
                }
                return Optional.ofNullable(user);
            }
            return Optional.empty();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching user by email {}: {}", email, e.getMessage());
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
                        int purchasedBefore = currentReferrer != null ? currentReferrer.getPurchasedCredits() : 0;
                        int bonusBefore = currentReferrer != null ? currentReferrer.getBonusCredits() : 0;
                        if (purchasedBefore == 0 && bonusBefore == 0 && currentReferrer != null && currentReferrer.getWalletCredits() > 0) {
                            purchasedBefore = currentReferrer.getWalletCredits();
                        }
                        int before = purchasedBefore + bonusBefore;
                        int bonusAfter = bonusBefore + rewardCredits;
                        int after = before + rewardCredits;
                        WalletTransaction rewardTx = WalletTransaction.builder()
                                .id(rewardTxRef.getId())
                                .uid(finalReferrer.getUid())
                                .type("REFERRAL_BONUS")
                                .amount(rewardCredits)
                                .balanceBefore(before)
                                .balanceAfter(after)
                                .purchasedBefore(purchasedBefore)
                                .purchasedAfter(purchasedBefore)
                                .bonusBefore(bonusBefore)
                                .bonusAfter(bonusAfter)
                                .bonusDelta(rewardCredits)
                                .description("Referral reward for inviting " + newUser.getEmail())
                                .createdAt(System.currentTimeMillis())
                                .build();

                        newUser.setReferredBy(finalReferrer.getReferralCode());
                        transaction.update(referrerRef,
                                "walletCredits", after,
                                "purchasedCredits", purchasedBefore,
                                "bonusCredits", bonusAfter);
                        transaction.set(rewardTxRef, rewardTx);
                    }
                }

                if (newUser.getWalletCredits() > 0 && newUser.getPurchasedCredits() == 0 && newUser.getBonusCredits() == 0) {
                    newUser.setBonusCredits(newUser.getWalletCredits());
                }
                transaction.set(newUserRef, newUser);
                if (newUser.getBonusCredits() > 0) {
                    var signupTxRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION)
                            .document("first_login_" + newUser.getUid());
                    WalletTransaction signupTx = WalletTransaction.builder()
                            .id(signupTxRef.getId())
                            .uid(newUser.getUid())
                            .type("FIRST_LOGIN_BONUS")
                            .amount(newUser.getBonusCredits())
                            .balanceBefore(0)
                            .balanceAfter(newUser.getPurchasedCredits() + newUser.getBonusCredits())
                            .purchasedBefore(0)
                            .purchasedAfter(newUser.getPurchasedCredits())
                            .bonusBefore(0)
                            .bonusAfter(newUser.getBonusCredits())
                            .bonusDelta(newUser.getBonusCredits())
                            .description("First time login reward")
                            .createdAt(System.currentTimeMillis())
                            .build();
                    transaction.set(signupTxRef, signupTx);
                }
                return newUser;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error creating user {} with referral reward: {}", newUser.getUid(), e.getMessage());
            throw new RuntimeException("Failed to save user", e);
        }
    }

    public void updateResume(String uid, String resumeText, String fileName,
                             String resumeSummary, List<String> resumeCategories) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("resumeText", resumeText);
            updates.put("resumeFileName", fileName);
            updates.put("resumeUploadedAt", System.currentTimeMillis());
            updates.put("resumeSummary", resumeSummary);
            updates.put("resumeCategories", resumeCategories == null ? List.of() : resumeCategories);

            firestore.collection(COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating resume for {}: {}", uid, e.getMessage());
            throw new RuntimeException("Failed to update resume", e);
        }
    }

    public void updateResumeInsights(String uid, String summary, List<String> categories) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("resumeSummary", summary);
            updates.put("resumeCategories", categories == null ? List.of() : categories);
            firestore.collection(COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating resume insights for {}: {}", uid, e.getMessage());
        }
    }

    public void updateInterviewPreferences(String uid, String interviewRole, String experienceLevel) {
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("interviewRole", interviewRole);
            updates.put("experienceLevel", experienceLevel);
            firestore.collection(COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error updating interview preferences for {}: {}", uid, e.getMessage());
            throw new RuntimeException("Failed to update interview preferences", e);
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
