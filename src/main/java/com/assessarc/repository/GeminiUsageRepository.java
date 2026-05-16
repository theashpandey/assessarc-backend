package com.assessarc.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.assessarc.model.GeminiUsageLog;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.WriteResult;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Slf4j
@Repository
@RequiredArgsConstructor
public class GeminiUsageRepository {

    private static final String COLLECTION = "gemini_usage_logs";
    private final Firestore firestore;

    public void save(GeminiUsageLog usageLog) {
        if (usageLog.getId() == null || usageLog.getId().isBlank()) {
            usageLog.setId(UUID.randomUUID().toString());
        }
        ApiFutures.addCallback(
                firestore.collection(COLLECTION).document(usageLog.getId()).set(usageLog),
                new ApiFutureCallback<>() {
                    @Override
                    public void onFailure(Throwable t) {
                        log.warn("Failed to save Gemini usage log: {}", t.getMessage());
                    }

                    @Override
                    public void onSuccess(WriteResult result) {
                        // Monitoring write is intentionally fire-and-forget.
                    }
                },
                MoreExecutors.directExecutor());
    }

    public List<GeminiUsageLog> findBetween(long fromInclusive, long toExclusive) {
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                    .whereGreaterThanOrEqualTo("createdAt", fromInclusive)
                    .whereLessThan("createdAt", toExclusive)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().get().getDocuments();

            return docs.stream()
                    .map(doc -> {
                        GeminiUsageLog usageLog = doc.toObject(GeminiUsageLog.class);
                        if (usageLog != null) usageLog.setId(doc.getId());
                        return usageLog;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch Gemini usage logs", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to fetch Gemini usage logs", e);
        }
    }
}
