package com.javadrill.service;

import com.javadrill.dto.Dto;
import com.javadrill.model.Interview;
import com.javadrill.model.User;
import com.javadrill.repository.InterviewRepository;
import com.javadrill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(DEFAULT_ZONE);

    private final UserRepository userRepository;
    private final InterviewRepository interviewRepository;

    public Dto.AdminUserAnalyticsResponse getUserAnalytics(String from, String to) {
        Range range = resolveRange(from, to);
        Range today = todayRange();

        List<User> users = userRepository.findAll();
        List<Interview> interviews = interviewRepository.findAll();
        List<User> usersInRange = users.stream()
                .filter(user -> inRange(user.getCreatedAt(), range))
                .toList();
        List<User> activeUsersInRange = users.stream()
                .filter(user -> inRange(user.getLastActiveAt(), range))
                .toList();
        List<Interview> interviewsInRange = interviews.stream()
                .filter(interview -> inRange(primaryInterviewTime(interview), range))
                .toList();

        int avgScore = (int) Math.round(interviews.stream()
                .map(Interview::getScores)
                .filter(scores -> scores != null && scores.getOverall() > 0)
                .mapToInt(Interview.Scores::getOverall)
                .average()
                .orElse(0));

        int bestScore = interviews.stream()
                .map(Interview::getScores)
                .filter(scores -> scores != null)
                .mapToInt(Interview.Scores::getOverall)
                .max()
                .orElse(0);

        Dto.AdminUserStats stats = Dto.AdminUserStats.builder()
                .totalUsers(users.size())
                .usersInRange(usersInRange.size())
                .newUsersToday((int) users.stream().filter(user -> inRange(user.getCreatedAt(), today)).count())
                .dailyActiveUsers((int) users.stream().filter(user -> inRange(user.getLastActiveAt(), today)).count())
                .activeUsersInRange(activeUsersInRange.size())
                .usersWithResume((int) users.stream().filter(user -> hasText(user.getResumeText())).count())
                .totalInterviews(interviews.size())
                .interviewsInRange(interviewsInRange.size())
                .interviewsToday((int) interviews.stream().filter(interview -> inRange(primaryInterviewTime(interview), today)).count())
                .completedInterviews((int) interviews.stream().filter(interview -> "COMPLETED".equals(status(interview))).count())
                .pendingInterviews((int) interviews.stream().filter(interview -> "ANALYSIS_PENDING".equals(status(interview))).count())
                .startedInterviews((int) interviews.stream().filter(interview -> "STARTED".equals(status(interview))).count())
                .totalCreditsInWallets(users.stream().mapToInt(User::getWalletCredits).sum())
                .avgScore(avgScore)
                .bestScore(bestScore)
                .build();

        return Dto.AdminUserAnalyticsResponse.builder()
                .from(range.fromDate().toString())
                .to(range.toDateInclusive().toString())
                .stats(stats)
                .usersByDay(groupByDay(usersInRange, User::getCreatedAt))
                .interviewsByDay(groupByDay(interviewsInRange, this::primaryInterviewTime))
                .interviewsByStatus(groupByValue(interviewsInRange, interview -> status(interview).toLowerCase(Locale.ROOT)))
                .usersByExperienceLevel(groupByValue(users, user -> blankToUnknown(user.getExperienceLevel())))
                .interviewsByRole(groupByValue(interviewsInRange, interview -> blankToUnknown(interview.getInterviewRole())))
                .recentUsers(users.stream()
                        .sorted(Comparator.comparingLong(User::getCreatedAt).reversed())
                        .limit(12)
                        .map(this::toAdminUserItem)
                        .toList())
                .build();
    }

    private <T> List<Dto.AdminMetricBucket> groupByDay(List<T> items, Function<T, Long> timeExtractor) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T item : items) {
            long millis = timeExtractor.apply(item);
            if (millis <= 0) continue;
            String key = Instant.ofEpochMilli(millis).atZone(DEFAULT_ZONE).toLocalDate().toString();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Dto.AdminMetricBucket.builder()
                        .key(entry.getKey())
                        .count(entry.getValue())
                        .build())
                .toList();
    }

    private <T> List<Dto.AdminMetricBucket> groupByValue(List<T> items, Function<T, String> valueExtractor) {
        return items.stream()
                .collect(Collectors.groupingBy(item -> blankToUnknown(valueExtractor.apply(item)), Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> Dto.AdminMetricBucket.builder()
                        .key(entry.getKey())
                        .count(entry.getValue().intValue())
                        .build())
                .toList();
    }

    private Dto.AdminUserItem toAdminUserItem(User user) {
        return Dto.AdminUserItem.builder()
                .uid(user.getUid())
                .name(user.getName())
                .email(user.getEmail())
                .walletCredits(user.getWalletCredits())
                .hasResume(hasText(user.getResumeText()))
                .totalInterviews(user.getTotalInterviews())
                .avgScore(user.getAvgScore())
                .createdAt(formatMillis(user.getCreatedAt()))
                .lastActiveAt(formatMillis(user.getLastActiveAt()))
                .build();
    }

    private Range resolveRange(String from, String to) {
        LocalDate fromDate = from == null || from.isBlank()
                ? LocalDate.now(DEFAULT_ZONE).minusDays(30)
                : LocalDate.parse(from, DAY_FMT);
        LocalDate toDate = to == null || to.isBlank()
                ? LocalDate.now(DEFAULT_ZONE)
                : LocalDate.parse(to, DAY_FMT);
        return new Range(fromDate, toDate,
                fromDate.atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli(),
                toDate.plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli());
    }

    private Range todayRange() {
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        return new Range(today, today,
                today.atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant().toEpochMilli());
    }

    private boolean inRange(long millis, Range range) {
        return millis >= range.fromMillis() && millis < range.toMillis();
    }

    private long primaryInterviewTime(Interview interview) {
        return interview.getCompletedAt() > 0 ? interview.getCompletedAt() : interview.getStartedAt();
    }

    private String status(Interview interview) {
        return interview.getStatus() == null || interview.getStatus().isBlank() ? "UNKNOWN" : interview.getStatus();
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String formatMillis(long millis) {
        return millis > 0 ? DISPLAY_FMT.format(Instant.ofEpochMilli(millis)) : "";
    }

    private record Range(LocalDate fromDate, LocalDate toDateInclusive, long fromMillis, long toMillis) {}
}
