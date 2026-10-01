package com.smartspend.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByUserIdAndSessionIdOrderByCreatedAtAsc(UUID userId, UUID sessionId);

    @Query("SELECT DISTINCT c.sessionId FROM ChatMessage c WHERE c.userId = :userId ORDER BY c.sessionId")
    List<UUID> findDistinctSessionIdsByUserId(@Param("userId") UUID userId);
}
