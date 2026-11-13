package com.smartspend.transaction;

import com.smartspend.common.ResourceNotFoundException;
import com.smartspend.document.DocumentRepository;
import com.smartspend.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PendingTransactionController {

    private final PendingTransactionService service;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;

    @GetMapping("/documents/{id}/pending-transactions")
    public ResponseEntity<List<PendingTransactionResponse>> list(@PathVariable UUID id) {
        UUID userId = getCurrentUserId();
        requireOwnedDocument(id, userId);
        return ResponseEntity.ok(service.list(userId, id));
    }

    @PutMapping("/pending-transactions/{id}")
    public ResponseEntity<PendingTransactionResponse> update(
            @PathVariable UUID id, @Valid @RequestBody UpdatePendingTransactionRequest request) {
        return ResponseEntity.ok(service.update(getCurrentUserId(), id, request));
    }

    @PostMapping("/documents/{id}/pending-transactions/confirm")
    public ResponseEntity<ConfirmResult> confirm(
            @PathVariable UUID id, @Valid @RequestBody ConfirmRequest request) {
        UUID userId = getCurrentUserId();
        requireOwnedDocument(id, userId);
        return ResponseEntity.ok(service.confirm(userId, id, request.getIds()));
    }

    private void requireOwnedDocument(UUID documentId, UUID userId) {
        documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    private UUID getCurrentUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();
    }
}
