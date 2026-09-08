package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.ShopMember.MemberRole;
import com.plstk.loyaltybot.entity.importing.BrandAlias;
import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.PublicPriceStrategy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.service.AuthorizationService;
import com.plstk.loyaltybot.service.importing.BrandAliasAdminService;
import com.plstk.loyaltybot.service.importing.BrandAliasValidationException;
import com.plstk.loyaltybot.service.importing.MailboxConnectionService;
import com.plstk.loyaltybot.service.importing.MailboxPollingService;
import com.plstk.loyaltybot.service.importing.ManualImportUploadService;
import com.plstk.loyaltybot.service.importing.PollResult;
import com.plstk.loyaltybot.service.importing.SupplierSourceAdminService;
import com.plstk.loyaltybot.service.importing.SupplierSourceValidationException;
import com.plstk.loyaltybot.service.importing.SupplierSourceVersionConflictException;
import com.plstk.loyaltybot.service.importing.UpdateSupplierSourceRequest;
import com.plstk.loyaltybot.service.importing.IngestionResult;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxConnectionTestResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Mailbox/supplier/supplier-source admin endpoints for email-first ingestion (Prompt 02). Never
 * returns {@code encryptedSecret}/plaintext secrets, and never exposes anything about parsing,
 * matching or apply — those stay out of scope until later prompts.
 */
@RestController
@RequestMapping("/api/shops/{shopId}")
@RequiredArgsConstructor
public class SupplierImportAdminController {

    private final AuthorizationService authorizationService;
    private final MailboxConnectionService mailboxConnectionService;
    private final MailboxPollingService mailboxPollingService;
    private final SupplierSourceAdminService supplierSourceAdminService;
    private final ManualImportUploadService manualImportUploadService;
    private final BrandAliasAdminService brandAliasAdminService;

    // ========== Mailboxes ==========

    @GetMapping("/mailboxes")
    public ResponseEntity<List<MailboxConnectionResponse>> listMailboxes(
            @PathVariable String shopId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        List<MailboxConnectionResponse> response = mailboxConnectionService.list(shopId).stream()
                .map(MailboxConnectionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mailboxes")
    public ResponseEntity<MailboxConnectionResponse> createMailbox(
            @PathVariable String shopId,
            @Valid @RequestBody CreateMailboxRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        MailboxConnection connection = mailboxConnectionService.create(
                shopId, request.label(), request.host(), request.port(), request.username(),
                request.secret(), request.authMode(), request.folder(), request.useTls(), request.enabled());
        return ResponseEntity.ok(MailboxConnectionResponse.from(connection));
    }

    @PostMapping("/mailboxes/{mailboxId}/test")
    public ResponseEntity<TestConnectionResponse> testMailbox(
            @PathVariable String shopId, @PathVariable Long mailboxId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        try {
            MailboxConnectionTestResult result = mailboxConnectionService.testConnection(shopId, mailboxId);
            return ResponseEntity.ok(new TestConnectionResponse(result.success(), result.message()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).build();
        }
    }

    @PostMapping("/mailboxes/{mailboxId}/poll")
    public ResponseEntity<PollResponse> pollMailbox(
            @PathVariable String shopId, @PathVariable Long mailboxId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        if (mailboxConnectionService.find(shopId, mailboxId).isEmpty()) {
            return ResponseEntity.status(404).build();
        }
        PollResult result = mailboxPollingService.pollOne(mailboxId);
        return ResponseEntity.ok(new PollResponse(result.status(), result.ingestedCount(), result.skippedCount(), result.message()));
    }

    // ========== Suppliers ==========

    @GetMapping("/suppliers")
    public ResponseEntity<List<SupplierResponse>> listSuppliers(
            @PathVariable String shopId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(supplierSourceAdminService.listSuppliers(shopId).stream()
                .map(SupplierResponse::from)
                .toList());
    }

    @PostMapping("/suppliers")
    public ResponseEntity<SupplierResponse> createSupplier(
            @PathVariable String shopId, @Valid @RequestBody CreateSupplierRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        Supplier supplier = supplierSourceAdminService.createSupplier(shopId, request.name(), request.code());
        return ResponseEntity.ok(SupplierResponse.from(supplier));
    }

    // ========== Supplier sources ==========

    @GetMapping("/supplier-sources")
    public ResponseEntity<List<SupplierSourceResponse>> listSupplierSources(
            @PathVariable String shopId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(supplierSourceAdminService.listSources(shopId).stream()
                .map(SupplierSourceResponse::from)
                .toList());
    }

    @PostMapping("/supplier-sources")
    public ResponseEntity<SupplierSourceResponse> createSupplierSource(
            @PathVariable String shopId, @Valid @RequestBody CreateSupplierSourceRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        try {
            SupplierSource source = supplierSourceAdminService.createSource(
                    shopId, request.supplierId(), request.label(), request.mailboxConnectionId(),
                    request.senderAllowlist(), request.subjectPattern(), request.filenamePattern());
            return ResponseEntity.ok(SupplierSourceResponse.from(source));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/supplier-sources/{sourceId}")
    public ResponseEntity<?> updateSupplierSource(
            @PathVariable String shopId,
            @PathVariable Long sourceId,
            @RequestBody UpdateSupplierSourceRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        try {
            SupplierSource updated = supplierSourceAdminService.updateSource(shopId, sourceId, request);
            return ResponseEntity.ok(SupplierSourceResponse.from(updated));
        } catch (SupplierSourceVersionConflictException e) {
            return ResponseEntity.status(409).body(new VersionConflictResponse(e.getMessage(), e.getCurrentVersion()));
        } catch (SupplierSourceValidationException e) {
            return ResponseEntity.badRequest().body(new ValidationErrorResponse(e.getMessage(), e.getFieldErrors()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/supplier-sources/{sourceId}/graduate")
    public ResponseEntity<?> graduateSupplierSource(
            @PathVariable String shopId,
            @PathVariable Long sourceId,
            @RequestBody(required = false) GraduateRequest request,
            @AuthenticationPrincipal AdminUser user) {
        // Graduating a source can enable autoApply (unattended catalog writes), so it requires the
        // highest privilege level rather than the ADMIN floor used by other supplier-source edits.
        if (!authorizationService.hasRole(user, shopId, MemberRole.OWNER)) {
            return ResponseEntity.status(403).build();
        }
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirm());
        try {
            SupplierSource updated = supplierSourceAdminService.graduate(shopId, sourceId, confirm);
            return ResponseEntity.ok(SupplierSourceResponse.from(updated));
        } catch (SupplierSourceValidationException e) {
            return ResponseEntity.badRequest().body(new ValidationErrorResponse(e.getMessage(), e.getFieldErrors()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ========== Brand aliases (Stage 4) ==========

    @GetMapping("/brand-aliases")
    public ResponseEntity<List<BrandAliasResponse>> listBrandAliases(
            @PathVariable String shopId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(brandAliasAdminService.list(shopId).stream()
                .map(BrandAliasResponse::from)
                .toList());
    }

    @PostMapping("/brand-aliases")
    public ResponseEntity<?> createBrandAlias(
            @PathVariable String shopId,
            @Valid @RequestBody CreateBrandAliasRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        try {
            BrandAlias created = brandAliasAdminService.create(
                    shopId, request.canonicalBrand(), request.alias(), user != null ? user.getEmail() : null);
            return ResponseEntity.ok(BrandAliasResponse.from(created));
        } catch (BrandAliasValidationException e) {
            return ResponseEntity.badRequest().body(new ValidationErrorResponse(e.getMessage(), e.getFieldErrors()));
        }
    }

    @DeleteMapping("/brand-aliases/{aliasId}")
    public ResponseEntity<?> deleteBrandAlias(
            @PathVariable String shopId, @PathVariable Long aliasId, @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(403).build();
        }
        try {
            brandAliasAdminService.delete(shopId, aliasId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ========== Manual upload fallback ==========

    @PostMapping(value = "/imports/manual-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadManually(
            @PathVariable String shopId,
            @RequestParam Long supplierSourceId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @AuthenticationPrincipal AdminUser user) {
        if (!authorizationService.hasRole(user, shopId, MemberRole.ADMIN)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            IngestionResult result = manualImportUploadService.upload(
                    shopId, supplierSourceId, file, requestId, user != null ? user.getId() : null);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ManualUploadResponse.from(result));
        } catch (ManualImportUploadService.ManualUploadTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new UploadErrorResponse("FILE_TOO_LARGE", e.getMessage()));
        } catch (ManualImportUploadService.ManualUploadValidationException e) {
            return ResponseEntity.badRequest()
                    .body(new UploadErrorResponse("INVALID_FILE", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(new UploadErrorResponse("INGESTION_FAILED", "Failed to store uploaded file"));
        }
    }

    // ========== DTOs ==========

    public record CreateMailboxRequest(
            @NotBlank String label,
            @NotBlank String host,
            @NotNull Integer port,
            @NotBlank String username,
            @NotBlank String secret,
            MailAuthMode authMode,
            String folder,
            Boolean useTls,
            Boolean enabled) {
    }

    public record MailboxConnectionResponse(
            Long id,
            String label,
            String host,
            Integer port,
            String username,
            MailAuthMode authMode,
            String folder,
            Boolean useTls,
            Boolean enabled,
            LocalDateTime lastPollAt,
            LocalDateTime lastPollSuccessAt,
            String lastPollError) {

        static MailboxConnectionResponse from(MailboxConnection c) {
            return new MailboxConnectionResponse(
                    c.getId(), c.getLabel(), c.getHost(), c.getPort(), c.getUsername(), c.getAuthMode(),
                    c.getFolder(), c.getUseTls(), c.getEnabled(), c.getLastPollAt(), c.getLastPollSuccessAt(),
                    c.getLastPollError());
        }
    }

    public record TestConnectionResponse(boolean success, String message) {
    }

    public record PollResponse(String status, int ingestedCount, int skippedCount, String message) {
    }

    public record ManualUploadResponse(
            Long importFileId,
            Long batchId,
            String status,
            boolean alreadyExisted) {

        static ManualUploadResponse from(IngestionResult result) {
            return new ManualUploadResponse(
                    result.importFile().getId(),
                    result.importBatch().getId(),
                    result.importBatch().getStatus().name(),
                    result.alreadyExisted());
        }
    }

    public record UploadErrorResponse(String code, String message) {
    }

    public record CreateSupplierRequest(@NotBlank String name, String code) {
    }

    public record SupplierResponse(Long id, String name, String code, Boolean active) {
        static SupplierResponse from(Supplier s) {
            return new SupplierResponse(s.getId(), s.getName(), s.getCode(), s.getActive());
        }
    }

    public record CreateSupplierSourceRequest(
            @NotNull Long supplierId,
            @NotBlank String label,
            Long mailboxConnectionId,
            String senderAllowlist,
            String subjectPattern,
            String filenamePattern) {
    }

    public record SupplierSourceResponse(
            Long id,
            Long version,
            String label,
            Long supplierId,
            String supplierName,
            Long mailboxConnectionId,
            String senderAllowlist,
            String subjectPattern,
            String filenamePattern,
            Boolean enabled,
            SnapshotMode snapshotMode,
            String snapshotScope,
            BigDecimal commissionPercentOverride,
            PublicPriceStrategy publicPriceStrategy,
            PriceRoundingPolicy roundingPolicy,
            Boolean shadowMode,
            Boolean autoApply,
            BigDecimal aiAutoApproveMinScoreOverride,
            BigDecimal aiMinConfidenceOverride) {

        static SupplierSourceResponse from(SupplierSource s) {
            return new SupplierSourceResponse(
                    s.getId(), s.getVersion(), s.getLabel(), s.getSupplier().getId(), s.getSupplier().getName(),
                    s.getMailboxConnection() != null ? s.getMailboxConnection().getId() : null,
                    s.getSenderAllowlist(), s.getSubjectPattern(), s.getFilenamePattern(),
                    s.getEnabled(), s.getSnapshotMode(), s.getSnapshotScope(), s.getCommissionPercentOverride(),
                    s.getPublicPriceStrategy(), s.getRoundingPolicy(), s.getShadowMode(), s.getAutoApply(),
                    s.getAiAutoApproveMinScoreOverride(), s.getAiMinConfidenceOverride());
        }
    }

    public record ValidationErrorResponse(String message, Map<String, String> fieldErrors) {
    }

    public record CreateBrandAliasRequest(@NotBlank String canonicalBrand, @NotBlank String alias) {
    }

    public record BrandAliasResponse(
            Long id, String canonicalBrand, String alias, String normalizedAlias, String createdBy, LocalDateTime createdAt) {
        static BrandAliasResponse from(BrandAlias a) {
            return new BrandAliasResponse(
                    a.getId(), a.getCanonicalBrand(), a.getAlias(), a.getNormalizedAlias(), a.getCreatedBy(), a.getCreatedAt());
        }
    }

    public record VersionConflictResponse(String message, Long currentVersion) {
    }

    public record GraduateRequest(Boolean confirm) {
    }
}
