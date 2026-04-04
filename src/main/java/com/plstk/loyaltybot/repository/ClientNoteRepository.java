package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.ClientNote;
import com.plstk.loyaltybot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientNoteRepository extends JpaRepository<ClientNote, Long> {
    
    /**
     * Получает активные заметки для клиента (не архивированные),
     * отсортированные по дате создания (новые первые)
     */
    List<ClientNote> findByCustomerAndIsArchivedFalseOrderByCreatedAtDesc(User customer);
    
    /**
     * Получает все заметки для клиента (включая архивированные),
     * отсортированные по дате создания (новые первые)
     */
    List<ClientNote> findByCustomerOrderByCreatedAtDesc(User customer);
    
    /**
     * Получает архивированные заметки для клиента
     */
    List<ClientNote> findByCustomerAndIsArchivedTrueOrderByArchivedAtDesc(User customer);
    
    /**
     * Считает количество активных заметок для клиента
     */
    long countByCustomerAndIsArchivedFalse(User customer);
    
    /**
     * Получает самую старую активную заметку для клиента
     * (для автоматической архивации при превышении лимита)
     */
    @Query("SELECT cn FROM ClientNote cn WHERE cn.customer = :customer AND cn.isArchived = false ORDER BY cn.createdAt ASC")
    List<ClientNote> findOldestActiveNotes(@Param("customer") User customer);
    
    /**
     * Получает заметки, созданные конкретным сотрудником
     */
    List<ClientNote> findByCreatedByOrderByCreatedAtDesc(User createdBy);
    
    /**
     * Удаляет все заметки для клиента (для GDPR / удаления аккаунта)
     */
    void deleteAllByCustomer(User customer);
}

