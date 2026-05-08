package com.javadrill.service;

import com.google.firebase.auth.FirebaseToken;
import com.javadrill.config.AppProperties;
import com.javadrill.dto.Dto;
import com.javadrill.model.User;
import com.javadrill.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_AVATAR_URL = "/default-avatar.svg";

    private final UserRepository userRepository;
    private final AppProperties props;
    private final AdminAuthService adminAuthService;

    public Dto.AuthResponse loginOrRegister(String uid, FirebaseToken token) {
        return loginOrRegister(uid, token, null);
    }

    public Dto.AuthResponse loginOrRegister(String uid, FirebaseToken token, String referralCode) {
        var existing = userRepository.findById(uid);
        boolean isNew = existing.isEmpty();

        User user;
        if (isNew) {
            user = User.builder()
                    .uid(uid)
                    .name(token.getName() != null ? token.getName() : "User")
                    .email(token.getEmail())
                    .photoUrl(resolvePhotoUrl(token))
                    .walletCredits(props.getWallet().getSignupBonus())
                    .createdAt(System.currentTimeMillis())
                    .lastActiveAt(System.currentTimeMillis())
                    .totalInterviews(0)
                    .avgScore(0.0)
                    .bestScore(0)
                    .referralCode(generateReferralCode(uid))
                    .build();
            user = userRepository.createUserWithReferralReward(user, referralCode, 10);
            log.info("New user registered: {} ({})", uid, token.getEmail());
        } else {
            user = existing.get();
            if (user.getReferralCode() == null || user.getReferralCode().isBlank()) {
                user.setReferralCode(generateReferralCode(uid));
                userRepository.save(user);
            }
            if (user.getPhotoUrl() == null || user.getPhotoUrl().isBlank()) {
                user.setPhotoUrl(resolvePhotoUrl(token));
                userRepository.save(user);
            }
            userRepository.updateLastActive(uid);
        }

        return Dto.AuthResponse.builder()
                .uid(user.getUid())
                .name(user.getName())
                .email(user.getEmail())
                .photoUrl(user.getPhotoUrl())
                .walletCredits(user.getWalletCredits())
                .hasResume(user.getResumeText() != null && !user.getResumeText().isBlank())
                .interviewRole(user.getInterviewRole())
                .experienceLevel(user.getExperienceLevel())
                .isNewUser(isNew)
                .totalInterviews(user.getTotalInterviews())
                .avgScore(user.getAvgScore())
                .referralCode(user.getReferralCode())
                .isAdmin(adminAuthService.isAdmin(user.getUid(), user.getEmail()))
                .build();
    }

    public Dto.UserProfileResponse getProfile(String uid) {
        var user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found: " + uid));
        return Dto.UserProfileResponse.builder()
                .uid(user.getUid())
                .name(user.getName())
                .email(user.getEmail())
                .photoUrl(user.getPhotoUrl())
                .walletCredits(user.getWalletCredits())
                .hasResume(user.getResumeText() != null && !user.getResumeText().isBlank())
                .resumeFileName(user.getResumeFileName())
                .resumeUploadedAt(user.getResumeUploadedAt())
                .interviewRole(user.getInterviewRole())
                .experienceLevel(user.getExperienceLevel())
                .createdAt(user.getCreatedAt())
                .totalInterviews(user.getTotalInterviews())
                .avgScore(user.getAvgScore())
                .bestScore(user.getBestScore())
                .referralCode(user.getReferralCode())
                .referredBy(user.getReferredBy())
                .isAdmin(adminAuthService.isAdmin(user.getUid(), user.getEmail()))
                .build();
    }

    private String generateReferralCode(String uid) {
        String clean = uid.replaceAll("[^A-Za-z0-9]", "");
        return ("JD" + clean.substring(0, Math.min(8, clean.length()))).toUpperCase(Locale.ROOT);
    }

    private String resolvePhotoUrl(FirebaseToken token) {
        Object picture = token.getClaims().get("picture");
        if (picture instanceof String photoUrl && !photoUrl.isBlank()) {
            return photoUrl;
        }
        return DEFAULT_AVATAR_URL;
    }

}
