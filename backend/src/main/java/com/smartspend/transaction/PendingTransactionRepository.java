package com.smartspend.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingTransactionRepository extends JpaRepository<PendingTransaction, UUID> {

    List<PendingTransaction> findByDocumentIdAndUserIdOrderByTransactionDateDesc(UUID documentId, UUID userId);

    Optional<PendingTransaction> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentIdAndUserId(UUID documentId, UUID userId);
}
