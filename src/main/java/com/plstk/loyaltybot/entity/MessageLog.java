package com.plstk.loyaltybot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Лог отправленных сообщений клиентам.
 * Используется для anti-spam и аналитики.
 */
@Entity
@Table(name = "message_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Получатель сообщения
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Тип сообщения
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;
    
    /**
     * Шаблон (если использовался)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private MessageTemplate template;
    
    /**
     * Отправленный текст (уже с подставленными переменными)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /**
     * Отправитель (админ) - null для авто-сообщений
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by_id")
    private User sentBy;
    
    /**
     * Дата и время отправки
     */
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
    
    /**
     * Статус доставки
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.SENT;
    
    /**
     * Ошибка доставки (если есть)
     */
    private String errorMessage;
    
    /**
     * Связанная ачивка (если сообщение об ачивке)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achievement_id")
    private CustomerAchievement achievement;
    
    /**
     * Связанный сигнал (если сообщение из сигнала)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id")
    private OwnerSignal signal;
    
    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
    
    /**
     * Типы сообщений
     */
    public enum MessageType {
        /**
         * Ручное сообщение от админа
         */
        MANUAL("Ручное"),
        
        /**
         * Авто-триггер
         */
        AUTO_TRIGGER("Авто-триггер"),
        
        /**
         * Уведомление об ачивке
         */
        ACHIEVEMENT("Ачивка"),
        
        /**
         * Уведомление о статусе
         */
        STATUS_CHANGE("Смена статуса"),
        
        /**
         * Напоминание
         */
        REMINDER("Напоминание"),
        
        /**
         * Акция/промо
         */
        PROMOTION("Акция"),
        
        /**
         * Системное уведомление
         */
        SYSTEM("Системное");
        
        private final String displayName;
        
        MessageType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Статус доставки
     */
    public enum DeliveryStatus {
        SENT("Отправлено"),
        DELIVERED("Доставлено"),
        FAILED("Ошибка"),
        BLOCKED("Заблокирован");
        
        private final String displayName;
        
        DeliveryStatus(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
}

