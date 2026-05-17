package com.assessarc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Slf4j
@Component
public class AiConcurrencyLimiter {

    public enum Lane {
        AUDIO_TRANSCRIPTION,
        ANSWER_FEEDBACK,
        SCORE_CALCULATION,
        CODING_EVALUATION,
        QUESTION_GENERATION,
        GENERAL
    }

    private final Semaphore audioTranscription = new Semaphore(160, true);
    private final Semaphore answerFeedback = new Semaphore(180, true);
    private final Semaphore scoreCalculation = new Semaphore(80, true);
    private final Semaphore codingEvaluation = new Semaphore(80, true);
    private final Semaphore questionGeneration = new Semaphore(120, true);
    private final Semaphore general = new Semaphore(120, true);

    public <T> T call(Lane lane, Supplier<T> supplier) {
        Semaphore semaphore = semaphoreFor(lane);
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiService.GeminiUnavailableException("AI service is busy. Please try again.");
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    public Lane laneFor(String callType) {
        String normalized = callType == null ? "" : callType.toLowerCase();
        if (normalized.contains("audio_transcription")) return Lane.AUDIO_TRANSCRIPTION;
        if (normalized.contains("feedback") || normalized.contains("transcript_correction")) return Lane.ANSWER_FEEDBACK;
        if (normalized.contains("score") || normalized.contains("analysis")) return Lane.SCORE_CALCULATION;
        if (normalized.contains("coding")) return Lane.CODING_EVALUATION;
        if (normalized.contains("question_generation") || normalized.contains("resume_parse")) return Lane.QUESTION_GENERATION;
        return Lane.GENERAL;
    }

    private Semaphore semaphoreFor(Lane lane) {
        return switch (lane) {
            case AUDIO_TRANSCRIPTION -> audioTranscription;
            case ANSWER_FEEDBACK -> answerFeedback;
            case SCORE_CALCULATION -> scoreCalculation;
            case CODING_EVALUATION -> codingEvaluation;
            case QUESTION_GENERATION -> questionGeneration;
            case GENERAL -> general;
        };
    }
}
