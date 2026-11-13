package com.smartspend.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByUserIdOrderByTransactionDateDesc(UUID userId);

    List<Transaction> findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(
            UUID userId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.userId = :userId AND t.type = :type AND t.transactionDate BETWEEN :startDate AND :endDate")
    BigDecimal sumByUserIdAndTypeAndDateRange(
            @Param("userId") UUID userId,
            @Param("type") String type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);


    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.userId = :userId AND t.categoryId = :categoryId AND t.type = 'EXPENSE' AND t.transactionDate BETWEEN :startDate AND :endDate")
    BigDecimal sumExpensesByCategoryId(
            @Param("userId") UUID userId,
            @Param("categoryId") UUID categoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Rows of {@code [categoryId (nullable UUID), total (BigDecimal)]}. */
    @Query("SELECT t.categoryId, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = 'EXPENSE' AND t.transactionDate BETWEEN :startDate AND :endDate GROUP BY t.categoryId")
    List<Object[]> sumExpensesGroupedByCategory(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Rows of {@code [normalizedDescription, total, count]}, biggest total first. */
    @Query("SELECT lower(trim(t.description)), SUM(t.amount), COUNT(t) FROM Transaction t WHERE t.userId = :userId AND t.type = 'EXPENSE' AND t.description IS NOT NULL AND trim(t.description) <> '' AND t.transactionDate BETWEEN :startDate AND :endDate GROUP BY lower(trim(t.description)) ORDER BY SUM(t.amount) DESC")
    List<Object[]> topMerchants(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    List<Transaction> findByUserIdAndTypeAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
            UUID userId, String type, LocalDate from);

    @Query("SELECT DISTINCT t.userId FROM Transaction t")
    List<UUID> findDistinctUserIds();

    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.userId = :userId AND t.transactionDate = :date AND t.amount = :amount AND t.type = :type AND lower(trim(coalesce(t.description, ''))) = :description")
    boolean existsDuplicate(
            @Param("userId") UUID userId,
            @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount,
            @Param("type") String type,
            @Param("description") String description);
}
