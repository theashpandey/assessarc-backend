package com.javadrill.service;

import com.javadrill.dto.Dto;
import com.javadrill.model.GeminiUsageLog;
import com.javadrill.repository.GeminiUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GeminiMonitoringService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final GeminiUsageRepository usageRepository;

    public Dto.GeminiUsageReport getReport(String from, String to, String month, String userId, String interviewId) {
        TimeRange range = resolveRange(from, to, month);
        List<GeminiUsageLog> logs = usageRepository.findBetween(range.fromMillis(), range.toMillis()).stream()
                .filter(log -> userId == null || userId.isBlank() || userId.equals(log.getUserId()))
                .filter(log -> interviewId == null || interviewId.isBlank() || interviewId.equals(log.getInterviewId()))
                .toList();

        Dto.GeminiUsageTotals totals = totals(logs);

        return Dto.GeminiUsageReport.builder()
                .from(range.fromDate())
                .to(range.toDateInclusive())
                .total(totals)
                .byDay(group(logs, GeminiUsageLog::getDay, false))
                .byMonth(group(logs, GeminiUsageLog::getMonth, false))
                .byUser(group(logs, log -> blankToUnknown(log.getUserId()), true))
                .byInterview(group(logs, log -> blankToUnknown(log.getInterviewId()), true))
                .byCallType(group(logs, log -> blankToUnknown(log.getCallType()), true))
                .build();
    }

    private List<Dto.GeminiUsageBucket> group(List<GeminiUsageLog> logs, KeyExtractor extractor, boolean sortByRequests) {
        Map<String, List<GeminiUsageLog>> grouped = new LinkedHashMap<>();
        for (GeminiUsageLog log : logs) {
            String key = blankToUnknown(extractor.key(log));
            grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(log);
        }
        var stream = grouped.entrySet().stream()
                .map(entry -> Dto.GeminiUsageBucket.builder()
                        .key(entry.getKey())
                        .totals(totals(entry.getValue()))
                        .build());
        return (sortByRequests
                ? stream.sorted((a, b) -> Integer.compare(b.getTotals().getRequestCount(), a.getTotals().getRequestCount()))
                : stream.sorted((a, b) -> b.getKey().compareTo(a.getKey())))
                .toList();
    }

    private Dto.GeminiUsageTotals totals(List<GeminiUsageLog> logs) {
        int requestCount = logs.size();
        int successCount = (int) logs.stream().filter(log -> "SUCCESS".equals(log.getStatus())).count();
        int failedCount = requestCount - successCount;
        int inputTokens = logs.stream().mapToInt(GeminiUsageLog::getInputTokens).sum();
        int outputTokens = logs.stream().mapToInt(GeminiUsageLog::getOutputTokens).sum();
        int totalTokens = logs.stream().mapToInt(GeminiUsageLog::getTotalTokens).sum();

        return Dto.GeminiUsageTotals.builder()
                .requestCount(requestCount)
                .successCount(successCount)
                .failedCount(failedCount)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .build();
    }

    private TimeRange resolveRange(String from, String to, String month) {
        LocalDate fromDate;
        LocalDate toDateExclusive;

        if (month != null && !month.isBlank()) {
            YearMonth ym = YearMonth.parse(month, MONTH_FMT);
            fromDate = ym.atDay(1);
            toDateExclusive = ym.plusMonths(1).atDay(1);
        } else {
            fromDate = from == null || from.isBlank()
                    ? LocalDate.now(DEFAULT_ZONE).minusDays(30)
                    : LocalDate.parse(from, DAY_FMT);
            LocalDate toInclusive = to == null || to.isBlank()
                    ? LocalDate.now(DEFAULT_ZONE)
                    : LocalDate.parse(to, DAY_FMT);
            toDateExclusive = toInclusive.plusDays(1);
        }

        long fromMillis = fromDate.atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli();
        long toMillis = toDateExclusive.atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli();
        return new TimeRange(fromMillis, toMillis, fromDate.toString(), toDateExclusive.minusDays(1).toString());
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    @FunctionalInterface
    private interface KeyExtractor {
        String key(GeminiUsageLog log);
    }

    private record TimeRange(long fromMillis, long toMillis, String fromDate, String toDateInclusive) {}
}
