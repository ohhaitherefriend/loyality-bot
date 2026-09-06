package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.service.TokenEncryptionService;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxConnectionConfig;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxConnectionTestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Shop-scoped CRUD + test-connection for {@link MailboxConnection}. The plaintext secret only
 * ever exists transiently: it arrives in the create request, is immediately encrypted via
 * {@link TokenEncryptionService} before the entity is saved, and is decrypted again only for the
 * duration of one {@link MailboxClient} call — never logged, never included in any response DTO.
 */
@Service
@RequiredArgsConstructor
public class MailboxConnectionService {

    private final MailboxConnectionRepository mailboxConnectionRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final MailboxClient mailboxClient;

    public List<MailboxConnection> list(String shopId) {
        return mailboxConnectionRepository.findByShopId(shopId);
    }

    public Optional<MailboxConnection> find(String shopId, Long id) {
        return mailboxConnectionRepository.findByShopIdAndId(shopId, id);
    }

    @Transactional
    public MailboxConnection create(
            String shopId, String label, String host, int port, String username, String secret,
            MailAuthMode authMode, String folder, Boolean useTls, Boolean enabled) {

        MailboxConnection connection = MailboxConnection.builder()
                .shopId(shopId)
                .label(label)
                .host(host)
                .port(port)
                .username(username)
                .encryptedSecret(tokenEncryptionService.encrypt(secret))
                .authMode(authMode)
                .folder(folder == null || folder.isBlank() ? "INBOX" : folder.trim())
                .useTls(useTls == null || useTls)
                .enabled(enabled == null || enabled)
                .build();
        return mailboxConnectionRepository.save(connection);
    }

    public MailboxConnectionTestResult testConnection(String shopId, Long mailboxId) {
        MailboxConnection connection = mailboxConnectionRepository.findByShopIdAndId(shopId, mailboxId)
                .orElseThrow(() -> new IllegalArgumentException("Mailbox " + mailboxId + " not found for shop " + shopId));
        String plainSecret;
        try {
            plainSecret = tokenEncryptionService.decrypt(connection.getEncryptedSecret());
        } catch (Exception e) {
            return MailboxConnectionTestResult.failure("Stored secret could not be decrypted");
        }
        MailboxConnectionConfig config = new MailboxConnectionConfig(
                connection.getHost(), connection.getPort(), connection.getUsername(),
                plainSecret, Boolean.TRUE.equals(connection.getUseTls()), connection.getFolder());
        return mailboxClient.testConnection(config);
    }
}
