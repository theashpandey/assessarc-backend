package com.javadrill.repository;

import com.google.cloud.firestore.Firestore;
import com.javadrill.model.WalletTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class WalletTransactionRepository {

    private static final String COLLECTION = "wallet_transactions";
    private final Firestore firestore;

    public WalletTransaction save(WalletTransaction tx) {
        try {
            if (tx.getId() == null || tx.getId().isBlank()) {
                tx.setId(UUID.randomUUID().toString());
            }
            firestore.collection(COLLECTION).document(tx.getId()).set(tx).get();
            return tx;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving wallet transaction for user {}: {}", tx.getUid(), e.getMessage());
            throw new RuntimeException("Failed to save wallet transaction", e);
        }
    }

    public List<WalletTransaction> findByUid(String uid, int limit) {
        try {
            return firestore.collection(COLLECTION)
                    .whereEqualTo("uid", uid)
                    .get().get()
                    .getDocuments().stream()
                    .map(doc -> {
                        WalletTransaction tx = doc.toObject(WalletTransaction.class);
                        if (tx != null) tx.setId(doc.getId());
                        return tx;
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(WalletTransaction::getCreatedAt).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching wallet transactions for user {}: {}", uid, e.getMessage());
            return List.of();
        }
    }
}
