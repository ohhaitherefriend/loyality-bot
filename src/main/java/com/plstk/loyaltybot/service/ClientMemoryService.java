package com.plstk.loyaltybot.service;

import com.plstk.loyaltybot.entity.ClientNote;
import com.plstk.loyaltybot.entity.CustomerStatus;
import com.plstk.loyaltybot.entity.User;
import com.plstk.loyaltybot.repository.ClientNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис памяти о клиентах.
 * 
 * Позволяет персоналу сохранять короткие заметки о клиентах,
 * которые отображаются при следующих визитах.
 * 
 * Подготовлен для интеграции с AI:
 * - getCustomerSummary() возвращает структурированный "портрет" клиента
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientMemoryService {
    
    private final ClientNoteRepository clientNoteRepository;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    
    // ========== Операции с заметками ==========
    
    /**
     * Добавляет заметку о клиенте.
     * Если активных заметок >= MAX_ACTIVE_NOTES_PER_CUSTOMER, 
     * автоматически архивирует самую старую.
     */
    @Transactional
    public ClientNote addNote(User customer, String text, User createdBy) {
        // Валидация текста
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Текст заметки не может быть пустым");
        }
        
        String trimmedText = text.trim();
        if (trimmedText.length() > ClientNote.MAX_TEXT_LENGTH) {
            trimmedText = trimmedText.substring(0, ClientNote.MAX_TEXT_LENGTH);
        }
        
        // Проверяем лимит активных заметок
        long activeCount = clientNoteRepository.countByCustomerAndIsArchivedFalse(customer);
        
        if (activeCount >= ClientNote.MAX_ACTIVE_NOTES_PER_CUSTOMER) {
            // Архивируем самую старую
            List<ClientNote> oldestNotes = clientNoteRepository.findOldestActiveNotes(customer);
            if (!oldestNotes.isEmpty()) {
                ClientNote oldest = oldestNotes.get(0);
                oldest.archive(createdBy);
                clientNoteRepository.save(oldest);
                log.info("Auto-archived oldest note {} for customer {}", oldest.getId(), customer.getId());
            }
        }
        
        // Создаём новую заметку
        ClientNote note = ClientNote.builder()
            .customer(customer)
            .text(trimmedText)
            .createdBy(createdBy)
            .isArchived(false)
            .build();
        
        ClientNote savedNote = clientNoteRepository.save(note);
        log.info("Added note {} for customer {} by staff {}", savedNote.getId(), customer.getId(), createdBy.getId());
        
        return savedNote;
    }
    
    /**
     * Получает активные заметки о клиенте
     */
    public List<ClientNote> getActiveNotes(User customer) {
        return clientNoteRepository.findByCustomerAndIsArchivedFalseOrderByCreatedAtDesc(customer);
    }
    
    /**
     * Получает все заметки о клиенте (включая архивные)
     */
    public List<ClientNote> getAllNotes(User customer) {
        return clientNoteRepository.findByCustomerOrderByCreatedAtDesc(customer);
    }
    
    /**
     * Архивирует заметку
     */
    @Transactional
    public void archiveNote(Long noteId, User archivedBy) {
        ClientNote note = clientNoteRepository.findById(noteId)
            .orElseThrow(() -> new IllegalArgumentException("Заметка не найдена: " + noteId));
        
        note.archive(archivedBy);
        clientNoteRepository.save(note);
        log.info("Archived note {} by staff {}", noteId, archivedBy.getId());
    }
    
    /**
     * Восстанавливает заметку из архива
     */
    @Transactional
    public void restoreNote(Long noteId, User restoredBy) {
        ClientNote note = clientNoteRepository.findById(noteId)
            .orElseThrow(() -> new IllegalArgumentException("Заметка не найдена: " + noteId));
        
        // Проверяем лимит
        long activeCount = clientNoteRepository.countByCustomerAndIsArchivedFalse(note.getCustomer());
        if (activeCount >= ClientNote.MAX_ACTIVE_NOTES_PER_CUSTOMER) {
            throw new IllegalStateException("Достигнут лимит активных заметок (" + 
                ClientNote.MAX_ACTIVE_NOTES_PER_CUSTOMER + ")");
        }
        
        note.restore();
        clientNoteRepository.save(note);
        log.info("Restored note {} by staff {}", noteId, restoredBy.getId());
    }
    
    /**
     * Удаляет заметку (только для админов)
     */
    @Transactional
    public void deleteNote(Long noteId) {
        clientNoteRepository.deleteById(noteId);
        log.info("Deleted note {}", noteId);
    }
    
    /**
     * Обновляет текст заметки
     */
    @Transactional
    public ClientNote updateNote(Long noteId, String newText, User updatedBy) {
        ClientNote note = clientNoteRepository.findById(noteId)
            .orElseThrow(() -> new IllegalArgumentException("Заметка не найдена: " + noteId));
        
        String trimmedText = newText.trim();
        if (trimmedText.length() > ClientNote.MAX_TEXT_LENGTH) {
            trimmedText = trimmedText.substring(0, ClientNote.MAX_TEXT_LENGTH);
        }
        
        note.setText(trimmedText);
        ClientNote saved = clientNoteRepository.save(note);
        log.info("Updated note {} by staff {}", noteId, updatedBy.getId());
        
        return saved;
    }
    
    // ========== Client Snapshot (для кассира) ==========
    
    /**
     * Формирует "Client Snapshot" для отображения кассиру при вводе purchaseCode.
     * Включает: имя, статус, визиты, траты, последние заметки.
     */
    public String getClientSnapshot(User customer) {
        StringBuilder snapshot = new StringBuilder();
        
        // Заголовок
        snapshot.append("🧠 *Информация о клиенте*\n\n");
        
        // Основная информация
        snapshot.append("👤 *").append(getCustomerDisplayName(customer)).append("*\n");
        snapshot.append("📊 Статус: ").append(getStatusEmoji(customer.getCustomerStatus()))
            .append(" ").append(getStatusText(customer.getCustomerStatus())).append("\n");
        
        // Статистика
        Integer visits = customer.getVisitsCount();
        Integer purchases = customer.getPurchasesCount();
        Double totalSpend = customer.getTotalSpend();
        
        if (purchases != null && purchases > 0) {
            snapshot.append("🛒 Покупок: ").append(purchases);
            if (visits != null && visits > 0 && !visits.equals(purchases)) {
                snapshot.append(" (визитов: ").append(visits).append(")");
            }
            snapshot.append("\n");
        }
        
        if (totalSpend != null && totalSpend > 0) {
            snapshot.append("💰 Всего потрачено: ").append(String.format("%.0f", totalSpend)).append(" руб.");
            if (purchases != null && purchases > 0) {
                double avgCheck = totalSpend / purchases;
                snapshot.append(" (средн. чек: ").append(String.format("%.0f", avgCheck)).append(")");
            }
            snapshot.append("\n");
        }
        
        // Последний визит
        if (customer.getLastPurchaseAt() != null) {
            snapshot.append("📅 Последний визит: ").append(customer.getLastPurchaseAt().format(DATE_FORMATTER)).append("\n");
        }
        
        // Заметки персонала
        List<ClientNote> notes = getActiveNotes(customer);
        if (!notes.isEmpty()) {
            snapshot.append("\n📝 *Заметки персонала:*\n");
            for (ClientNote note : notes) {
                snapshot.append(note.getDisplayText()).append("\n");
            }
        }
        
        return snapshot.toString();
    }
    
    // ========== AI-ready методы ==========
    
    /**
     * Возвращает структурированную информацию о клиенте для AI-интеграции.
     * 
     * Может использоваться для:
     * - Генерации персонализированных рекомендаций
     * - Автоматических сообщений
     * - Анализа поведения клиентов
     */
    public CustomerSummary getCustomerSummary(User customer) {
        List<ClientNote> activeNotes = getActiveNotes(customer);
        
        return CustomerSummary.builder()
            .customerId(customer.getId())
            .displayName(getCustomerDisplayName(customer))
            .status(customer.getCustomerStatus())
            .statusText(getStatusText(customer.getCustomerStatus()))
            .purchasesCount(customer.getPurchasesCount() != null ? customer.getPurchasesCount() : 0)
            .visitsCount(customer.getVisitsCount() != null ? customer.getVisitsCount() : 0)
            .totalSpend(customer.getTotalSpend() != null ? customer.getTotalSpend() : 0.0)
            .averageCheck(customer.getAverageCheck())
            .firstPurchaseAt(customer.getFirstPurchaseAt())
            .lastPurchaseAt(customer.getLastPurchaseAt())
            .daysSinceLastPurchase(customer.getDaysSinceLastPurchase())
            .notes(activeNotes.stream().map(ClientNote::getText).collect(Collectors.toList()))
            .notesCount(activeNotes.size())
            .isNewCustomer(customer.getPurchasesCount() == null || customer.getPurchasesCount() == 0)
            .isVip(customer.getCustomerStatus() == CustomerStatus.VIP)
            .isLost(customer.getCustomerStatus() == CustomerStatus.LOST)
            .build();
    }
    
    /**
     * Генерирует краткий "портрет" клиента в текстовом виде для AI
     */
    public String getCustomerPortrait(User customer) {
        CustomerSummary summary = getCustomerSummary(customer);
        
        StringBuilder portrait = new StringBuilder();
        portrait.append("Клиент: ").append(summary.getDisplayName()).append("\n");
        portrait.append("Статус: ").append(summary.getStatusText()).append("\n");
        portrait.append("Покупок: ").append(summary.getPurchasesCount()).append("\n");
        portrait.append("Общая сумма: ").append(String.format("%.0f", summary.getTotalSpend())).append(" руб.\n");
        portrait.append("Средний чек: ").append(String.format("%.0f", summary.getAverageCheck())).append(" руб.\n");
        
        if (summary.getDaysSinceLastPurchase() != null) {
            portrait.append("Дней с последнего визита: ").append(summary.getDaysSinceLastPurchase()).append("\n");
        }
        
        if (!summary.getNotes().isEmpty()) {
            portrait.append("Заметки персонала:\n");
            for (String note : summary.getNotes()) {
                portrait.append("- ").append(note).append("\n");
            }
        }
        
        return portrait.toString();
    }
    
    // ========== Вспомогательные методы ==========
    
    private String getCustomerDisplayName(User customer) {
        StringBuilder name = new StringBuilder();
        if (customer.getFirstName() != null) {
            name.append(customer.getFirstName());
        }
        if (customer.getLastName() != null) {
            if (name.length() > 0) name.append(" ");
            name.append(customer.getLastName());
        }
        if (name.length() == 0 && customer.getUsername() != null) {
            name.append("@").append(customer.getUsername());
        }
        if (name.length() == 0) {
            name.append("Клиент #").append(customer.getId());
        }
        return name.toString();
    }
    
    private String getStatusEmoji(CustomerStatus status) {
        if (status == null) return "🆕";
        return switch (status) {
            case NEW -> "🆕";
            case REGULAR -> "⭐";
            case VIP -> "👑";
            case LOST -> "😢";
        };
    }
    
    private String getStatusText(CustomerStatus status) {
        if (status == null) return "Новый";
        return switch (status) {
            case NEW -> "Новый";
            case REGULAR -> "Постоянный";
            case VIP -> "VIP";
            case LOST -> "Давно не был";
        };
    }
    
    // ========== DTO для AI-интеграции ==========
    
    /**
     * Структурированная информация о клиенте для AI
     */
    @lombok.Data
    @lombok.Builder
    public static class CustomerSummary {
        private Long customerId;
        private String displayName;
        private CustomerStatus status;
        private String statusText;
        private int purchasesCount;
        private int visitsCount;
        private double totalSpend;
        private double averageCheck;
        private LocalDateTime firstPurchaseAt;
        private LocalDateTime lastPurchaseAt;
        private Long daysSinceLastPurchase;
        private List<String> notes;
        private int notesCount;
        private boolean isNewCustomer;
        private boolean isVip;
        private boolean isLost;
    }
}

