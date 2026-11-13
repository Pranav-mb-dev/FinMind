package com.smartspend.alert;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertWebhookNotifierTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);

    private Alert alert() {
        return Alert.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).type("BUDGET_EXCEEDED")
                .severity("CRITICAL").message("Over budget").dedupeKey("k").createdAt(Instant.now()).build();
    }

    @Test
    void blankUrlDisablesNotifier() {
        AlertWebhookNotifier n = new AlertWebhookNotifier("  ", restTemplate);

        assertThat(n.isEnabled()).isFalse();
        n.sendAfterCommit(alert(), "a@b.c");
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postsPayloadWithoutActiveTransaction() {
        AlertWebhookNotifier n = new AlertWebhookNotifier("http://n8n/webhook/x", restTemplate);
        Alert a = alert();

        n.sendAfterCommit(a, "a@b.c");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(eq("http://n8n/webhook/x"), body.capture(), eq(Void.class));
        Map<String, Object> payload = (Map<String, Object>) body.getValue();
        assertThat(payload).containsEntry("type", "BUDGET_EXCEEDED")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("userEmail", "a@b.c")
                .containsEntry("message", "Over budget");
    }

    @Test
    void failedWebhookIsSwallowed() {
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("timeout"));
        AlertWebhookNotifier n = new AlertWebhookNotifier("http://n8n/webhook/x", restTemplate);

        assertThatCode(() -> n.sendAfterCommit(alert(), "a@b.c")).doesNotThrowAnyException();
    }
}
