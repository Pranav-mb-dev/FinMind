package com.smartspend.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    List<Alert> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndReadFalse(UUID userId);

    boolean existsByUserIdAndDedupeKey(UUID userId, String dedupeKey);

    Optional<Alert> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE Alert a SET a.read = true WHERE a.userId = :userId AND a.read = false")
    int markAllRead(@Param("userId") UUID userId);
}
