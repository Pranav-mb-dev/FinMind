package com.smartspend.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional push of new alerts to an n8n webhook. Does nothing when no URL is configured. A failed or slow
 * call is logged and swallowed: alerts exist in the app regardless, and the webhook must never break a request.
 */
@Component
@Slf4j
public class AlertWebhookNotifier {

    private static final int TIMEOUT_MS = 2000;

    private final String url;
    private final RestTemplate restTemplate;

    @Autowired
    public AlertWebhookNotifier(@Value("${n8n.alert-webhook-url:}") String url) {
        this(url, defaultTemplate());
    }

    AlertWebhookNotifier(String url, RestTemplate restTemplate) {
        this.url = url == null ? "" : url.trim();
        this.restTemplate = restTemplate;
    }

    public boolean isEnabled() {
        return !url.isEmpty();
    }

    /** Sends once the surrounding transaction commits, or immediately when there is none. */
    public void sendAfterCommit(Alert alert, String userEmail) {
        if (!isEnabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(alert, userEmail);
                }
            });
        } else {
            send(alert, userEmail);
        }
    }

    void send(Alert alert, String userEmail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", alert.getId());
            payload.put("type", alert.getType());
            payload.put("severity", alert.getSeverity());
            payload.put("message", alert.getMessage());
            payload.put("createdAt", alert.getCreatedAt());
            payload.put("userEmail", userEmail);
            restTemplate.postForEntity(url, payload, Void.class);
        } catch (RuntimeException e) {
            log.warn("Alert webhook failed for alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    private static RestTemplate defaultTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
