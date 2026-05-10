package com.assessarc.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FirebaseConfig {

    private final AppProperties props;
    private final ResourceLoader resourceLoader;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        var resource = resourceLoader.getResource(props.getFirebase().getServiceAccountPath());
        var credentials = GoogleCredentials.fromStream(resource.getInputStream());
        var options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();
        var app = FirebaseApp.initializeApp(options);
        log.info("Firebase initialised successfully");
        return app;
    }

    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }
}
