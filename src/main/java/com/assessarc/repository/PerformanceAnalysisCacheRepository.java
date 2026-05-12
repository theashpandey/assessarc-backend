package com.assessarc.repository;

import com.assessarc.model.PerformanceAnalysisCache;
import com.google.cloud.firestore.Firestore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PerformanceAnalysisCacheRepository {

    private static final String COLLECTION = "performance_analysis_cache";
    private final Firestore firestore;

    public Optional<PerformanceAnalysisCache> findByUserId(String userId) {
        try {
            var doc = firestore.collection(COLLECTION).document(userId).get().get();
            if (!doc.exists()) return Optional.empty();
            PerformanceAnalysisCache cache = doc.toObject(PerformanceAnalysisCache.class);
            return Optional.ofNullable(cache);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while fetching performance analysis cache for user {}", userId);
            return Optional.empty();
        } catch (ExecutionException e) {
            log.warn("Failed to fetch performance analysis cache for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(PerformanceAnalysisCache cache) {
        try {
            firestore.collection(COLLECTION).document(cache.getUserId()).set(cache).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while saving performance analysis cache for user {}", cache.getUserId());
        } catch (ExecutionException e) {
            log.warn("Failed to save performance analysis cache for user {}: {}", cache.getUserId(), e.getMessage());
        }
    }
}
