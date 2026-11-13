package com.smartspend.alert;

import java.util.List;

public record AlertListResponse(long unreadCount, List<AlertResponse> alerts) {
}
