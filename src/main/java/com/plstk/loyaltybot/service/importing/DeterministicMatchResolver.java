package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Runs the deterministic matching stage order from docs/ARCHITECTURE.md §9.2/prompt 04 (Stage 4
 * production-hardening pass), in order:
 * <ol>
 *   <li>{@code SupplierProductLink} (supplier+SKU, supplier+barcode, or shop+fingerprint)</li>
 *   <li>exact, unique barcode against the catalog (no link needed)</li>
 *   <li>exact, unique {@code supplierArticle} against the catalog (no link needed - covers a
 *       supplier's very first batch against a product already catalogued with this same article by a
 *       legacy manual import)</li>
 *   <li>safe fingerprint against the catalog (exact structural match, no critical conflict)</li>
 *   <li>otherwise: top explainable fuzzy candidates, never auto-matched</li>
 * </ol>
 * Ambiguity (duplicate barcode, more than one fingerprint match) or a critical conflict at any
 * deterministic stage always falls through to the next stage instead of guessing - a false match is
 * worse than {@code NEEDS_REVIEW}/AI matching later (D-009/§9.4).
 */
@Service
public class DeterministicMatchResolver {

    private final SupplierProductLinkRepository supplierProductLinkRepository;
    private final ProductRepository productRepository;
    private final RowAttributeNormalizer normalizer;
    private final CriticalAttributeConflictChecker conflictChecker;
    private final CandidateSearchService candidateSearchService;
    private final BrandNormalizer brandNormalizer;
    private final BrandAliasResolver brandAliasResolver;

    public DeterministicMatchResolver(
            SupplierProductLinkRepository supplierProductLinkRepository,
            ProductRepository productRepository,
            RowAttributeNormalizer normalizer,
            CriticalAttributeConflictChecker conflictChecker,
            CandidateSearchService candidateSearchService,
            BrandNormalizer brandNormalizer,
            BrandAliasResolver brandAliasResolver) {
        this.supplierProductLinkRepository = supplierProductLinkRepository;
        this.productRepository = productRepository;
        this.normalizer = normalizer;
        this.conflictChecker = conflictChecker;
        this.candidateSearchService = candidateSearchService;
        this.brandNormalizer = brandNormalizer;
        this.brandAliasResolver = brandAliasResolver;
    }

    /** See {@link CandidateSearchService#startNewBatch} - call once per shop before this batch's row loop. */
    public void startNewBatch(String shopId) {
        candidateSearchService.startNewBatch(shopId);
    }

    public MatchResolution resolve(String shopId, Long supplierId, NormalizedRowData row) {
        Optional<MatchResolution> linked = resolveViaSupplierProductLink(shopId, supplierId, row);
        if (linked.isPresent()) {
            return linked.get();
        }

        Optional<MatchResolution> barcodeMatch = resolveViaExactBarcode(shopId, row);
        if (barcodeMatch.isPresent()) {
            return barcodeMatch.get();
        }

        Optional<MatchResolution> articleMatch = resolveViaExactSupplierArticle(shopId, supplierId, row);
        if (articleMatch.isPresent()) {
            return articleMatch.get();
        }

        List<ScoredCandidate> scored = candidateSearchService.search(shopId, row);

        Optional<MatchResolution> fingerprintMatch = resolveViaSafeFingerprint(row, scored);
        if (fingerprintMatch.isPresent()) {
            return fingerprintMatch.get();
        }

        return MatchResolution.unresolved(candidateSearchService.topCandidates(scored));
    }

    private Optional<MatchResolution> resolveViaSupplierProductLink(String shopId, Long supplierId, NormalizedRowData row) {
        if (row.externalSku() != null) {
            Optional<SupplierProductLink> link = supplierProductLinkRepository
                    .findByShopIdAndSupplierIdAndExternalSku(shopId, supplierId, row.externalSku());
            Optional<MatchResolution> resolved = asLearnedMatch(link);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        if (row.barcode() != null) {
            Optional<SupplierProductLink> link = supplierProductLinkRepository
                    .findByShopIdAndSupplierIdAndBarcode(shopId, supplierId, row.barcode());
            Optional<MatchResolution> resolved = asLearnedMatch(link);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        if (row.fingerprint() != null && !row.fingerprint().isBlank()) {
            List<SupplierProductLink> links = supplierProductLinkRepository
                    .findByShopIdAndFingerprintAndProductIsNotNull(shopId, row.fingerprint());
            // getId() on a lazy @ManyToOne proxy reads the FK column already held by the proxy and
            // never triggers a DB round-trip, so this is safe even though this resolver runs outside
            // any single open transaction (see MatchResolution javadoc).
            List<Long> distinctProductIds = links.stream().map(l -> l.getProduct().getId()).distinct().toList();
            if (distinctProductIds.size() == 1) {
                return Optional.of(MatchResolution.resolved(distinctProductIds.get(0), MatchDecisionType.LEARNED));
            }
            // 0 or ambiguous (>1 distinct product) - fall through rather than guessing.
        }
        return Optional.empty();
    }

    private Optional<MatchResolution> asLearnedMatch(Optional<SupplierProductLink> link) {
        return link.filter(l -> l.getProduct() != null)
                .map(l -> MatchResolution.resolved(l.getProduct().getId(), MatchDecisionType.LEARNED));
    }

    private Optional<MatchResolution> resolveViaExactBarcode(String shopId, NormalizedRowData row) {
        if (row.barcode() == null || row.barcode().isBlank()) {
            return Optional.empty();
        }
        List<Product> matches = productRepository.findAllByShopIdAndBarcode(shopId, row.barcode());
        if (matches.size() != 1) {
            // 0 -> no match; >1 -> duplicate barcode data anomaly, never auto-pick one.
            return Optional.empty();
        }
        Product candidate = matches.get(0);
        NormalizedRowData candidateAttributes = normalizer.normalizeProduct(candidate);
        List<String> conflicts = conflictChecker.findConflicts(row, candidateAttributes);
        if (!conflicts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MatchResolution.resolved(candidate.getId(), MatchDecisionType.EXACT));
    }

    /**
     * "Действительно стабильный идентификатор" is operationalized the same way barcode is: unique
     * within the shop AND clear of critical attribute conflicts (volume/concentration/shade/
     * tester/set) against the row - a coincidental article-number collision with a completely
     * different product is rejected instead of silently trusted.
     *
     * <p>Unlike barcode (a universal real-world product identifier that different suppliers
     * legitimately share for the same physical item), a supplier article/SKU is that supplier's
     * own internal numbering - two different suppliers' internal catalogues coincidentally using
     * the same article string for two completely unrelated products is common, not a signal of
     * the same product. {@code Product.supplierArticle} is a single denormalized column (whichever
     * supplier most recently wrote it), so a bare {@code shopId + supplierArticle} lookup is
     * effectively unscoped by supplier. Two independent safeguards are required before this is
     * trusted, neither sufficient alone (see docs/DECISIONS.md ADR-023/ADR-028):
     * <ol>
     *   <li>if this candidate product is already linked (via {@link SupplierProductLink}) to a
     *       <em>different</em> supplier, the coincidental match is rejected outright - this alone
     *       does NOT cover a candidate with no link to any supplier yet (e.g. a legacy manually
     *       catalogued product), which is the gap ADR-028 closes;</li>
     *   <li>the row's own brand must match (exactly, or via {@link BrandAliasResolver}/{@link
     *       BrandNormalizer} transliteration) the candidate's catalog brand - a coincidental article
     *       collision across two different brands (e.g. a Dior row against an existing Chanel
     *       product with no link yet) is never trusted as "confirmed supplier+article identity"
     *       just because the bare article string happens to match. Either side missing a brand
     *       entirely means identity can't be confirmed either way, so the match is rejected rather
     *       than assumed safe.</li>
     * </ol>
     */
    private Optional<MatchResolution> resolveViaExactSupplierArticle(String shopId, Long supplierId, NormalizedRowData row) {
        if (row.externalSku() == null || row.externalSku().isBlank()) {
            return Optional.empty();
        }
        List<Product> matches = productRepository.findAllByShopIdAndSupplierArticle(shopId, row.externalSku());
        if (matches.size() != 1) {
            return Optional.empty();
        }
        Product candidate = matches.get(0);
        if (supplierProductLinkRepository.existsByShopIdAndProductIdAndSupplierIdNot(shopId, candidate.getId(), supplierId)) {
            return Optional.empty();
        }
        if (!brandsConfirmIdentity(shopId, row.brand(), candidate.getBrand())) {
            return Optional.empty();
        }
        NormalizedRowData candidateAttributes = normalizer.normalizeProduct(candidate);
        List<String> conflicts = conflictChecker.findConflicts(row, candidateAttributes);
        if (!conflicts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MatchResolution.resolved(candidate.getId(), MatchDecisionType.EXACT));
    }

    /**
     * True only when both brands are present and are the same brand (exact normalized match, or a
     * known/transliteration alias per {@link BrandAliasResolver}) - the positive "confirmed
     * supplier+article identity" signal required by {@link #resolveViaExactSupplierArticle}
     * (ADR-028). A missing brand on either side is treated as "cannot confirm", not as "no
     * conflict" - unlike {@link CriticalAttributeConflictChecker}, which is deliberately lenient
     * about absent attributes, this is a required POSITIVE signal, not merely the absence of a
     * negative one.
     */
    private boolean brandsConfirmIdentity(String shopId, String rowBrand, String candidateBrand) {
        if (rowBrand == null || rowBrand.isBlank() || candidateBrand == null || candidateBrand.isBlank()) {
            return false;
        }
        String normalizedRow = brandNormalizer.normalize(rowBrand);
        String normalizedCandidate = brandNormalizer.normalize(candidateBrand);
        if (normalizedRow == null || normalizedRow.isEmpty()
                || normalizedCandidate == null || normalizedCandidate.isEmpty()) {
            return false;
        }
        if (normalizedRow.equals(normalizedCandidate)) {
            return true;
        }
        return brandAliasResolver.areAliases(shopId, rowBrand, candidateBrand);
    }

    private Optional<MatchResolution> resolveViaSafeFingerprint(NormalizedRowData row, List<ScoredCandidate> scored) {
        if (row.fingerprint() == null || row.fingerprint().isBlank()) {
            return Optional.empty();
        }
        List<ScoredCandidate> fingerprintMatches = scored.stream()
                .filter(c -> !c.hasConflicts())
                .filter(c -> row.fingerprint().equals(c.candidateAttributes().fingerprint()))
                .toList();
        if (fingerprintMatches.size() != 1) {
            // 0 -> no safe fingerprint match; >1 -> ambiguous, never auto-pick one.
            return Optional.empty();
        }
        return Optional.of(MatchResolution.resolved(fingerprintMatches.get(0).productId(), MatchDecisionType.EXACT));
    }
}
