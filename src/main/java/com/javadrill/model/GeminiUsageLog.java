package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiUsageLog {
    private String id;
    private String userId;
    private String interviewId;
    private String callType;
    private String status;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private long createdAt;
    private String day;
    private String month;
    private String errorMessage;
}
