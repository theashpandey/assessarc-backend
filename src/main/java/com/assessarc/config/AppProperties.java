package com.assessarc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "assessarc")
public class AppProperties {

    private Gemini gemini = new Gemini();
    private Firebase firebase = new Firebase();
    private Razorpay razorpay = new Razorpay();
    private Admin admin = new Admin();
    private Cors cors = new Cors();
    private Wallet wallet = new Wallet();

    @Data
    public static class Gemini {
        private String apiKey;
        private String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";
    }

    @Data
    public static class Firebase {
        private String serviceAccountPath = "file:/etc/secrets/firebase-service-account.json";
    }

    @Data
    public static class Razorpay {
        private String keyId;
        private String keySecret;
        private String accountNumber;
    }

    @Data
    public static class Admin {
        private String allowedEmails = "";
        private String allowedUids = "";
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of(
                "http://localhost:3000",
                "https://assessarc.vercel.app",
                "https://assessarc.app",
                "https://www.assessarc.app",
                "https://assessarc.onrender.com",
                "https://www.assessarc.com",
                "https://assessarc.com"
        );
    }

    @Data
    public static class Wallet {
        private int signupBonus = 10;   // 10 credits free = 1 interview
        private int price30min = 5;     // ₹5 equivalent
        private int price60min = 10;    // ₹10 equivalent
    }
}
