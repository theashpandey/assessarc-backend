package com.javadrill.controller;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.javadrill.dto.Dto;
import com.javadrill.security.FirebaseAuthFilter;
import com.javadrill.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/auth/login
     * Frontend sends Firebase ID token after Google Sign-In.
     * Returns user profile + wallet balance.
     */
    @PostMapping("/login")
    public ResponseEntity<Dto.AuthResponse> login(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Dto.LoginRequest req) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).build();
            }
            String idToken = authHeader.substring("Bearer ".length()).trim();
            if (idToken.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken);
            var response = authService.loginOrRegister(decoded.getUid(), decoded,
                    req != null ? req.getReferralCode() : null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
    }

    /**
     * GET /api/auth/me
     * Returns current user's profile.
     */
    @GetMapping("/me")
    public ResponseEntity<Dto.UserProfileResponse> me(Authentication auth) {
        String uid = (String) auth.getPrincipal();
        return ResponseEntity.ok(authService.getProfile(uid));
    }
}
