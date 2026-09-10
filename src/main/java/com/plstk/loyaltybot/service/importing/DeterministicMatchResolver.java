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

    public DeterministicMatchResolver(
            SupplierProductLinkRepository supplierProductLinkRepository,
            ProductRepository productRepository,
            RowAttributeNormalizer normalizer,
            CriticalAttributeConflictChecker conflictChecker,
            CandidateSearchService candidateSearchService) {
        this.supplierProductLinkRepository = supplierProductLinkRepository;
        this.productRepository = productRepository;
        this.normalizer = normalizer;
        this.conflictChecker = conflictChecker;
        this.candidateSearchService = candidateSearchService;
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
     * effectively unscoped by supplier. If this candidate product is already linked (via
     * {@link SupplierProductLink}) to a <em>different</em> supplier, that coincidental match is
     * rejected instead of silently substituting one supplier's price/stock onto another supplier's
     * product (see docs/DECISIONS.md ADR-023).
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
        NormalizedRowData candidateAttributes = normalizer.normalizeProduct(candidate);
        List<String> conflicts = conflictChecker.findConflicts(row, candidateAttributes);
        if (!conflicts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(MatchResolution.resolved(candidate.getId(), MatchDecisionType.EXACT));
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
