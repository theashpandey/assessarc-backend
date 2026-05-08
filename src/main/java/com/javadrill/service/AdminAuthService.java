package com.javadrill.service;

import com.google.firebase.auth.FirebaseToken;
import com.javadrill.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AppProperties props;

    public void requireAdmin(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("Unauthorized");
        }

        String uid = String.valueOf(auth.getPrincipal());
        String email = "";
        if (auth.getDetails() instanceof FirebaseToken token && token.getEmail() != null) {
            email = token.getEmail().toLowerCase(Locale.ROOT);
        }

        Set<String> allowedUids = splitCsv(props.getAdmin().getAllowedUids());
        Set<String> allowedEmails = splitCsv(props.getAdmin().getAllowedEmails()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        if (!allowedUids.contains(uid) && (email.isBlank() || !allowedEmails.contains(email))) {
            throw new RuntimeException("Unauthorized");
        }
    }

    public boolean isAdmin(String uid, String email) {
        Set<String> allowedUids = splitCsv(props.getAdmin().getAllowedUids());
        Set<String> allowedEmails = splitCsv(props.getAdmin().getAllowedEmails()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        String normalizedEmail = email == null ? "" : email.toLowerCase(Locale.ROOT);
        return (uid != null && allowedUids.contains(uid))
                || (!normalizedEmail.isBlank() && allowedEmails.contains(normalizedEmail));
    }

    private Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Stream.of(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }
}
