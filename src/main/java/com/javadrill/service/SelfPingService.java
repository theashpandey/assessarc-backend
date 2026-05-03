package com.javadrill.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;
@Slf4j
@Component
public class SelfPingService {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String SELF_URL = "https://javadrill.onrender.com/api/ping";

    @Scheduled(fixedRate = 5 * 60 * 1000) // every 5 minutes
    public void pingSelf() {
        try {
            restTemplate.getForObject(SELF_URL, String.class);
            log.info("🔁 Self-ping successful");
        } catch (Exception e) {
          log.info("⚠️ Self-ping failed: " + e.getMessage());
        }
    }
}
