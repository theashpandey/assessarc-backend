package com.assessarc.model;

import com.assessarc.dto.Dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Firestore collection: "performance_analysis_cache"
 * Document ID: Firebase UID. One current performance analysis per user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceAnalysisCache {
    private String userId;
    private String fingerprint;
    private List<String> interviewIds;
    private Dto.PerformanceAnalysisResponse analysis;
    private long generatedAt;
    private long updatedAt;
}
