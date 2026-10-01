package com.smartspend.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    List<Budget> findByUserId(UUID userId);

    Optional<Budget> findByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
