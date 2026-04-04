package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.ShopSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShopSettingsRepository extends JpaRepository<ShopSettings, Long> {
    
    /**
     * Получает первую (единственную) запись настроек магазина.
     * Используется для обратной совместимости (single-tenant режим).
     */
    Optional<ShopSettings> findFirstByOrderByIdAsc();
    
    /**
     * Находит настройки по shopId (multi-tenant режим).
     */
    Optional<ShopSettings> findByShopId(String shopId);
    
    /**
     * Проверяет существование настроек для shopId.
     */
    boolean existsByShopId(String shopId);
}



