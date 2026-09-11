package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the deterministic matching stage order from docs/ARCHITECTURE.md §9.2/prompt 04 (Stage 4
 * production-hardening pass), in order:
 * <ol>
 *   <li>{@code SupplierProductLink} (supplier+SKU, supplier+barcode, or shop+fingerprint)</li>
 *   <li>exact, unique barcode against the catalog (no link needed)</li>
 *   <li>exact, unique {@code supplierArticle} against the catalog (no link needed - covers a
 *       supplier's very first batch against a product already catalogued with this same article by a
 *       legacy manual import) AND a fingerprint-exact structural match (ADR-029: a shared article
 *       number under the SAME brand but a DIFFERENT product line - e.g. "Coco Mademoiselle" vs "No
 *       5" - must never auto-match just because brand and article coincide)</li>
 *   <li>safe fingerprint against the ENTIRE brand-scoped catalog, unbounded by any candidate-fetch
 *       limit (ADR-029: exact structural match, no critical conflict)</li>
 *   <li>otherwise: top explainable fuzzy candidates (bounded, ranked, for human/AI review only),
 *       never auto-matched</li>
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

    /**
     * Section 3 hardening: {@link #resolveViaSafeFingerprint} is deliberately unbounded (queries
     * and re-normalizes every product of the row's brand), which is correct for one row but, before
     * this cache, meant a brand with 300+ products got re-fetched from the DB AND re-normalized in
     * full for EVERY row of a large same-brand import batch (e.g. 300 rows x 300 products = ~90k
     * redundant normalizations for one file). Keyed by (shopId, brandTokens) since that's the only
     * input that determines the fetched+normalized set; evicted per shop at the start of every
     * batch by {@link #startNewBatch} - same lifecycle as {@link CandidateSearchService#startNewBatch}
     * and {@link SimpleProductCandidateFetcher}'s own cache, so alias/catalog changes between
     * batches are never served stale.
     */
    private final ConcurrentHashMap<BrandCacheKey, List<Map.Entry<Long, NormalizedRowData>>> brandFingerprintCache =
            new ConcurrentHashMap<>();

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
        brandFingerprintCache.keySet().removeIf(key -> key.shopId().equals(shopId));
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

        // ADR-029/031: this exact/unbounded check runs BEFORE the bounded fuzzy candidate search
        // below, and does not depend on it at all - a genuinely exact (fingerprint-equal) match
        // must never be missed just because a brand has more products than the fuzzy pool's
        // page-size cap, or because a noisy short/numeric name token diluted that pool's relevance
        // ranking. It also distinguishes NONE from AMBIGUOUS (ADR-031, Section 1 scenario B) -
        // these two are NOT the same signal and must never be collapsed into one boolean/Optional.
        FingerprintCheckResult fingerprintCheck = resolveViaSafeFingerprint(shopId, row);
        if (fingerprintCheck.outcome() == SearchCompleteness.IdentityOutcome.UNIQUE) {
            return MatchResolution.resolved(fingerprintCheck.uniqueProductId(), MatchDecisionType.EXACT);
        }
        if (fingerprintCheck.outcome() == SearchCompleteness.IdentityOutcome.AMBIGUOUS) {
            List<ScoredCandidate> ambiguousCandidates =
                    candidateSearchService.scoreProducts(shopId, row, fingerprintCheck.ambiguousProducts());
            return MatchResolution.unresolved(ambiguousCandidates, SearchCompleteness.ambiguous(
                    "more than one catalog product shares an identical structural fingerprint "
                            + "(brand+line+volume+concentration+shade+tester+set) - refusing to auto-pick "
                            + "either one or to treat this row as a safe NEW_PRODUCT",
                    RowAttributeNormalizer.NORMALIZATION_VERSION));
        }

        List<ScoredCandidate> scored = candidateSearchService.search(shopId, row);
        return MatchResolution.unresolved(candidateSearchService.topCandidates(scored), completeness(row));
    }

    /**
     * ADR-030/031: {@link #resolveViaSafeFingerprint} is UNBOUNDED (no candidate-fetch-limit at
     * all) once it actually runs - so "did it run" (had a usable brand + fingerprint) is precisely
     * "was the required exact-identity check complete" for this row. This does NOT depend on how
     * many fuzzy candidates were found - zero fuzzy candidates with a completed exact-identity
     * check is a legitimately safe signal for {@code NEW_PRODUCT}; zero fuzzy candidates WITHOUT
     * one is not. Only reached when the fingerprint check's own outcome was {@code NONE} (a
     * {@code UNIQUE}/{@code AMBIGUOUS} outcome already returned from {@link #resolve} above).
     */
    private SearchCompleteness completeness(NormalizedRowData row) {
        if (row.fingerprint() == null || row.fingerprint().isBlank()) {
            return SearchCompleteness.incomplete(
                    "row fingerprint could not be computed (name did not parse into any structured attributes)",
                    RowAttributeNormalizer.NORMALIZATION_VERSION);
        }
        if (row.brand() == null || row.brand().isBlank()) {
            return SearchCompleteness.incomplete(
                    "row has no brand - the unbounded exact-identity catalog check could not be scoped",
                    RowAttributeNormalizer.NORMALIZATION_VERSION);
        }
        return SearchCompleteness.completed(RowAttributeNormalizer.NORMALIZATION_VERSION);
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
        NormalizedRowData candidateAttributes = normalizer.normalizeProduct(shopId, candidate);
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
     * effectively unscoped by supplier. Three independent safeguards are required before this is
     * trusted, neither alone sufficient (see docs/DECISIONS.md ADR-023/ADR-028/ADR-029):
     * <ol>
     *   <li>if this candidate product is already linked (via {@link SupplierProductLink}) to a
     *       <em>different</em> supplier, the coincidental match is rejected outright - this alone
     *       does NOT cover a candidate with no link to any supplier yet (e.g. a legacy manually
     *       catalogued product), which is the gap ADR-028 closes;</li>
     *   <li>the row's own brand must match (exactly, or via {@link BrandAliasResolver}/{@link
     *       BrandNormalizer} transliteration) the candidate's catalog brand - a coincidental article
     *       collision across two different brands is never trusted as "confirmed supplier+article
     *       identity" just because the bare article string happens to match;</li>
     *   <li>the row's computed {@code fingerprint} must EXACTLY equal the candidate's - brand alone
     *       is not enough: two DIFFERENT products of the SAME brand (e.g. "Chanel Coco Mademoiselle
     *       100 ml" vs "Chanel No 5 100 ml") can share both brand and, coincidentally, a supplier
     *       article number, with zero attributes {@link CriticalAttributeConflictChecker} would
     *       flag as conflicting (the line/name text itself isn't a "critical attribute" it compares)
     *       - only a fully-matching fingerprint (brand+line+volume+concentration+shade+tester+set)
     *       confirms this is genuinely the same product, not merely the same brand and a coincidental
     *       article (ADR-029, reproduced by the report). This is the "exact match of characteristics
     *       and name" the user asked for as the alternative to trusting the bare article.</li>
     * </ol>
     */
    private Optional<MatchResolution> resolveViaExactSupplierArticle(String shopId, Long supplierId, NormalizedRowData row) {
        if (row.externalSku() == null || row.externalSku().isBlank()) {
            return Optional.empty();
        }
        if (row.fingerprint() == null || row.fingerprint().isBlank()) {
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
        NormalizedRowData candidateAttributes = normalizer.normalizeProduct(shopId, candidate);
        List<String> conflicts = conflictChecker.findConflicts(row, candidateAttributes);
        if (!conflicts.isEmpty()) {
            return Optional.empty();
        }
        if (!row.fingerprint().equals(candidateAttributes.fingerprint())) {
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

    /**
     * ADR-029 (six-bug hardening pass, second follow-up): previously ran against the bounded,
     * ranked output of {@code candidateSearchService.search} - a large brand or a noisy short
     * name token could dilute that bounded pool's relevance ranking enough that a genuinely
     * fingerprint-exact product got truncated away before ever reaching this check (reproduced by
     * the report: item #306 of a 305-item brand still missing from candidates despite multi-token
     * search + ranking). This now runs its OWN unbounded, brand-scoped query
     * ({@link ProductRepository#findAllByShopIdAndBrandTokenIn}, no page/limit at all) BEFORE the
     * bounded fuzzy search ever runs, so an exact structural match can never be hidden by any
     * candidate-fetch-limit or ranking heuristic - those remain solely a concern of the fuzzy/AI
     * suggestion path for human review, not of this deterministic auto-match decision.
     */
    private FingerprintCheckResult resolveViaSafeFingerprint(String shopId, NormalizedRowData row) {
        if (row.fingerprint() == null || row.fingerprint().isBlank()) {
            return FingerprintCheckResult.none();
        }
        if (row.brand() == null || row.brand().isBlank()) {
            // No brand signal to scope an otherwise-unbounded catalog query by - falls through to
            // the bounded fuzzy search instead of scanning the entire shop's catalog.
            return FingerprintCheckResult.none();
        }
        Set<String> brandTokens = brandAliasResolver.expand(shopId, row.brand());
        if (brandTokens.isEmpty()) {
            return FingerprintCheckResult.none();
        }
        List<Map.Entry<Long, NormalizedRowData>> normalizedBrandCandidates = brandFingerprintCache.computeIfAbsent(
                new BrandCacheKey(shopId, brandTokens),
                key -> productRepository.findAllByShopIdAndBrandTokenIn(key.shopId(), key.brandTokens()).stream()
                        .map(p -> Map.entry(p.getId(), normalizer.normalizeProduct(key.shopId(), p)))
                        .toList());
        List<Long> fingerprintMatches = normalizedBrandCandidates.stream()
                .filter(e -> conflictChecker.findConflicts(row, e.getValue()).isEmpty())
                .filter(e -> row.fingerprint().equals(e.getValue().fingerprint()))
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
        if (fingerprintMatches.isEmpty()) {
            return FingerprintCheckResult.none();
        }
        if (fingerprintMatches.size() > 1) {
            // ADR-031 (Section 1 scenario B): more than one catalog product structurally identical
            // to this row - a data-quality anomaly. This is NOT the same signal as "no match" and
            // must never be silently collapsed into it: it forbids both auto-picking either
            // candidate AND treating the row as safe grounds for a NEW_PRODUCT.
            List<Product> ambiguousProducts = productRepository.findByShopIdAndIdIn(shopId, fingerprintMatches);
            return FingerprintCheckResult.ambiguous(ambiguousProducts);
        }
        return FingerprintCheckResult.unique(fingerprintMatches.get(0));
    }

    private record BrandCacheKey(String shopId, Set<String> brandTokens) {
    }

    /**
     * ADR-031 (Section 1 scenario B): explicit three-way outcome of the unbounded safe-fingerprint
     * check - NEVER collapsed into a single {@code Optional}, which cannot distinguish "found
     * nothing" from "found more than one" (the exact ambiguity the report's second bug exploited).
     */
    private record FingerprintCheckResult(
            SearchCompleteness.IdentityOutcome outcome, Long uniqueProductId, List<Product> ambiguousProducts) {

        static FingerprintCheckResult none() {
            return new FingerprintCheckResult(SearchCompleteness.IdentityOutcome.NONE, null, List.of());
        }

        static FingerprintCheckResult unique(Long productId) {
            return new FingerprintCheckResult(SearchCompleteness.IdentityOutcome.UNIQUE, productId, List.of());
        }

        static FingerprintCheckResult ambiguous(List<Product> products) {
            return new FingerprintCheckResult(SearchCompleteness.IdentityOutcome.AMBIGUOUS, null, products);
        }
    }
}
