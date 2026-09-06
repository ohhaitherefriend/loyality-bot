package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.MatchDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchDecisionRepository extends JpaRepository<MatchDecision, Long> {

    List<MatchDecision> findByImportRowId(Long importRowId);

    /** Newest first: index 0 is the current decision, index 1 is the "previous decision" for audit display. */
    List<MatchDecision> findByImportRowIdOrderByDecidedAtDesc(Long importRowId);

    /**
     * Automation-rate denominator helper (Prompt 07 dashboard): a row that was ever touched by a
     * {@code HUMAN} decision does not count as fully automated, even if it ultimately reached
     * {@code APPLIED}.
     */
    @Query("SELECT COUNT(DISTINCT d.importRow.id) FROM MatchDecision d WHERE d.shopId = :shopId "
            + "AND d.decidedBy = com.plstk.loyaltybot.entity.importing.DecidedBy.HUMAN")
    long countDistinctRowsWithHumanDecision(@Param("shopId") String shopId);
}
