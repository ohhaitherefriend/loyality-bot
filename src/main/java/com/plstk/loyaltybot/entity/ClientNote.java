package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Заметки персонала о клиентах.
 * 
 * Позволяет сохранять короткие практические заметки о клиентах,
 * которые видны только персоналу при следующих визитах.
 * 
 * Ограничения:
 * - Максимум 5 активных заметок на клиента
 * - При добавлении 6-й старейшая автоматически архивируется
 * - Максимальная длина заметки: 200 символов
 */
@Entity
@Table(name = "client_notes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientNote {
    
    public static final int MAX_TEXT_LENGTH = 200;
    public static final int MAX_ACTIVE_NOTES_PER_CUSTOMER = 5;
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Клиент, к которому относится заметка
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;
    
    /**
     * Текст заметки (max 200 символов)
     */
    @Column(nullable = false, length = MAX_TEXT_LENGTH)
    private String text;
    
    /**
     * Сотрудник, создавший заметку
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;
    
    /**
     * Дата создания
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * Архивирована ли заметка
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;
    
    /**
     * Дата архивации (если архивирована)
     */
    private LocalDateTime archivedAt;
    
    /**
     * Сотрудник, архивировавший заметку
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by_id")
    private User archivedBy;
    
    /**
     * Дата последнего обновления
     */
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isArchived == null) {
            isArchived = false;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Архивирует заметку
     */
    public void archive(User archivedByUser) {
        this.isArchived = true;
        this.archivedAt = LocalDateTime.now();
        this.archivedBy = archivedByUser;
    }
    
    /**
     * Восстанавливает заметку из архива
     */
    public void restore() {
        this.isArchived = false;
        this.archivedAt = null;
        this.archivedBy = null;
    }
    
    /**
     * Возвращает краткое представление заметки для отображения
     */
    public String getDisplayText() {
        return "— " + text;
    }
    
    /**
     * Возвращает информацию о создателе заметки
     */
    public String getCreatorInfo() {
        if (createdBy == null) {
            return "Неизвестно";
        }
        String name = createdBy.getFirstName();
        if (createdBy.getLastName() != null) {
            name += " " + createdBy.getLastName().charAt(0) + ".";
        }
        return name;
    }
}

