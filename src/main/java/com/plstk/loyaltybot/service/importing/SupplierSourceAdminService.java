package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shop-scoped CRUD for {@link Supplier} and {@link SupplierSource} used by the mailbox/source
 * minimal admin UI (Prompt 02). Matching/parsing rules, snapshot policy tuning etc. remain out of
 * scope here — this only lets an operator wire "this mailbox + these filters -> this supplier".
 */
@Service
@RequiredArgsConstructor
public class SupplierSourceAdminService {

    private final SupplierRepository supplierRepository;
    private final SupplierSourceRepository supplierSourceRepository;
    private final MailboxConnectionRepository mailboxConnectionRepository;

    public List<Supplier> listSuppliers(String shopId) {
        return supplierRepository.findByShopId(shopId);
    }

    @Transactional
    public Supplier createSupplier(String shopId, String name, String code) {
        Supplier supplier = Supplier.builder()
                .shopId(shopId)
                .name(name)
                .code(code)
                .build();
        return supplierRepository.save(supplier);
    }

    public List<SupplierSource> listSources(String shopId) {
        return supplierSourceRepository.findByShopId(shopId);
    }

    @Transactional
    public SupplierSource createSource(
            String shopId, Long supplierId, String label, Long mailboxConnectionId,
            String senderAllowlist, String subjectPattern, String filenamePattern) {

        Supplier supplier = supplierRepository.findByShopIdAndId(shopId, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier " + supplierId + " not found for shop " + shopId));

        MailboxConnection mailboxConnection = null;
        if (mailboxConnectionId != null) {
            mailboxConnection = mailboxConnectionRepository.findByShopIdAndId(shopId, mailboxConnectionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Mailbox " + mailboxConnectionId + " not found for shop " + shopId));
        }

        validateRegexOrThrow(subjectPattern, "subjectPattern");
        validateRegexOrThrow(filenamePattern, "filenamePattern");

        SupplierSource source = SupplierSource.builder()
                .shopId(shopId)
                .supplier(supplier)
                .label(label)
                .mailboxConnection(mailboxConnection)
                .senderAllowlist(senderAllowlist)
                .subjectPattern(blankToNull(subjectPattern))
                .filenamePattern(blankToNull(filenamePattern))
                .build();
        return supplierSourceRepository.save(source);
    }

    private void validateRegexOrThrow(String pattern, String fieldName) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex for " + fieldName + ": " + e.getMessage());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
