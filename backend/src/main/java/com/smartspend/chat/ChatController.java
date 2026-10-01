package com.smartspend.chat;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.user.User;
import com.smartspend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @PostMapping
    public ResponseEntity<ChatMessageResponse> chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = getCurrentUserId();
        UUID sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID();

        ChatMessage userMessage = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .role("user")
                .content(request.getMessage())
                .build();
        chatMessageRepository.save(userMessage);

        String aiResponse;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/api/v1/chat",
                    Map.of(
                            "user_id", userId.toString(),
                            "session_id", sessionId.toString(),
                            "message", request.getMessage()
                    ),
                    Map.class);
            aiResponse = response != null ? (String) response.get("response") : "I'm sorry, I couldn't process your request.";
        } catch (Exception e) {
            aiResponse = "I'm sorry, the AI service is currently unavailable. Please try again later.";
        }

        ChatMessage assistantMessage = ChatMessage.builder()
                .userId(userId)
                .sessionId(sessionId)
                .role("assistant")
                .content(aiResponse)
                .build();
        chatMessageRepository.save(assistantMessage);

        return ResponseEntity.ok(ChatMessageResponse.builder()
                .id(assistantMessage.getId())
                .sessionId(sessionId)
                .role("assistant")
                .content(aiResponse)
                .createdAt(assistantMessage.getCreatedAt())
                .build());
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<UUID>> getSessions() {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatMessageRepository.findDistinctSessionIdsByUserId(userId));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<List<ChatMessageResponse>> getSessionMessages(@PathVariable UUID sessionId) {
        UUID userId = getCurrentUserId();
        List<ChatMessageResponse> messages = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(messages);
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return user.getId();
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        return ChatMessageResponse.builder()
                .id(m.getId())
                .sessionId(m.getSessionId())
                .role(m.getRole())
                .content(m.getContent())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
