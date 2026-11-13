package com.smartspend.alert;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID id, String type, String severity, String message, boolean read, Instant createdAt) {

    public static AlertResponse from(Alert a) {
        return new AlertResponse(a.getId(), a.getType(), a.getSeverity(), a.getMessage(), a.isRead(), a.getCreatedAt());
    }
}
