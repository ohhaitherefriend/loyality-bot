package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailboxConnectionRepository extends JpaRepository<MailboxConnection, Long> {

    List<MailboxConnection> findByShopId(String shopId);

    Optional<MailboxConnection> findByShopIdAndId(String shopId, Long id);

    List<MailboxConnection> findByEnabledTrue();
}
