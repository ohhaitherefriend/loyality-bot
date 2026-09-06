package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the automatic {@code STORED -&gt; PARSING -&gt; NORMALIZING} (or {@code QUARANTINED}/
 * {@code FAILED}) transition for one {@link ImportBatch} (docs/ARCHITECTURE.md §7/§8).
 *
 * <p>Deliberately NOT {@code @Transactional} itself: workbook IO and the AI HTTP call both happen
 * here, outside any DB transaction, and every persistence step is delegated to
 * {@link ImportBatchParseWriter}. Flow:
 * <ol>
 *   <li>atomically claim STORED -&gt; PARSING (idempotent no-op if already claimed/parsed elsewhere);</li>
 *   <li>if the source has a published ACTIVE rule whose header signature matches this file, reuse it
 *       without calling AI;</li>
 *   <li>otherwise ask {@link AiSpreadsheetLayoutDetector}, validate the response with
 *       {@link LayoutRuleValidator}, run an automatic preview, and only then publish a new rule
 *       version — an invalid/low-confidence layout quarantines the batch instead;</li>
 *   <li>fully parse with the resolved rule and apply batch-level guards (empty file, collapsed
 *       valid-row ratio which, for FULL snapshots, would otherwise be able to wipe the storefront);</li>
 *   <li>persist {@code ImportRow}s and move the batch to {@code NORMALIZING} on success.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchParsingService {

    private final ImportBatchRepository importBatchRepository;
    private final ImportRuleVersionRepository importRuleVersionRepository;
    private final ImportFileStorage importFileStorage;
    private final SpreadsheetParser spreadsheetParser;
    private final AiSpreadsheetLayoutDetector aiSpreadsheetLayoutDetector;
    private final LayoutRuleValidator layoutRuleValidator;
    private final SupplierImportProperties properties;
    private final ImportBatchParseWriter writer;

    public void parseBatch(Long batchId) {
        if (!writer.claimForParsing(batchId)) {
            log.debug("Batch {} is not in STORED status (already claimed/processed) - skipping", batchId);
            return;
        }

        ImportBatch batch = importBatchRepository.findByIdWithSupplierSourceAndFile(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found after claim"));
        SupplierSource supplierSource = batch.getSupplierSource();
        String shopId = batch.getShopId();

        try (InputStream fileStream = importFileStorage.open(batch.getImportFile().getStorageKey());
             Workbook workbook = WorkbookFactory.create(fileStream)) {

            RuleResolution resolution = resolveRule(shopId, supplierSource, workbook);
            if (resolution.quarantineReason() != null) {
                writer.finalizeQuarantine(batchId, resolution.quarantineReason());
                return;
            }

            LayoutRuleDefinition rule = resolution.rule();
            WorkbookParseResult result = spreadsheetParser.parse(workbook, rule, null);

            String guardFailure = evaluateBatchGuards(result);
            if (guardFailure != null) {
                writer.finalizeQuarantine(batchId, guardFailure);
                return;
            }

            writer.finalizeSuccess(batchId, resolution.ruleVersionEntity(), result);
        } catch (Exception e) {
            log.error("Batch {} failed with unexpected error during parsing", batchId, e);
            writer.finalizeFailed(batchId, "Unexpected parsing error: " + safeMessage(e));
        }
    }

    private RuleResolution resolveRule(String shopId, SupplierSource supplierSource, Workbook workbook) {
        Optional<ImportRuleVersion> activeVersion = importRuleVersionRepository.findByShopIdAndSupplierSourceIdAndStatus(
                shopId, supplierSource.getId(), RuleVersionStatus.ACTIVE);

        if (activeVersion.isPresent()) {
            LayoutRuleDefinition activeRule = parseStoredRule(activeVersion.get());
            if (activeRule != null && spreadsheetParser.matchesKnownLayout(workbook, activeRule)) {
                log.info("Reusing known ACTIVE rule version {} for supplierSource {} (no AI call)",
                        activeVersion.get().getId(), supplierSource.getId());
                return RuleResolution.reused(activeRule, activeVersion.get());
            }
            log.info("ACTIVE rule version {} no longer matches this file's header signature (schema drift) - "
                    + "falling back to AI layout detection", activeVersion.get().getId());
        }

        return detectNewLayout(shopId, supplierSource, workbook);
    }

    private RuleResolution detectNewLayout(String shopId, SupplierSource supplierSource, Workbook workbook) {
        List<LayoutSheetSample> sample = spreadsheetParser.sampleForAiDetection(workbook);
        LayoutDetectionRequest request = new LayoutDetectionRequest(
                supplierSource.getLabel(), supplierSource.getSnapshotScope(), sample);

        LayoutDetectionResponse response = aiSpreadsheetLayoutDetector.detect(request);
        if (!response.success()) {
            return RuleResolution.quarantined("AI layout detection failed: " + response.errorMessage());
        }

        LayoutRuleValidationResult validation = layoutRuleValidator.validate(response.rawContent());
        if (!validation.valid()) {
            return RuleResolution.quarantined(
                    "AI-generated layout rule failed validation: " + String.join("; ", validation.errors()));
        }

        LayoutRuleDefinition candidateRule = validation.rule();
        var signature = spreadsheetParser.computeHeaderSignature(workbook, candidateRule);
        if (signature.isEmpty()) {
            return RuleResolution.quarantined(
                    "AI-selected sheet(s) " + candidateRule.getSheetSelectors() + " not found in workbook");
        }
        candidateRule.setExpectedHeaderSignature(signature);

        SupplierImportProperties.Parser parserCfg = properties.getParser();
        WorkbookParseResult preview = spreadsheetParser.parse(workbook, candidateRule, parserCfg.getPreviewRowCount());
        if (preview.totalRows() == 0 || !preview.anySheetHeaderResolved()) {
            return RuleResolution.quarantined(
                    "AI-generated layout rule preview resolved zero rows (schema drift / low confidence)");
        }
        if (preview.validRowRatio() < parserCfg.getPreviewMinValidRowRatio()) {
            return RuleResolution.quarantined(String.format(
                    "AI-generated layout rule preview valid-row ratio %.2f below threshold %.2f",
                    preview.validRowRatio(), parserCfg.getPreviewMinValidRowRatio()));
        }

        ImportRuleVersion published = writer.publishNewRuleVersion(shopId, supplierSource, candidateRule);
        return RuleResolution.created(candidateRule, published);
    }

    private String evaluateBatchGuards(WorkbookParseResult result) {
        if (result.totalRows() == 0) {
            return "Parsed 0 rows from workbook; refusing to run assortment reconciliation on an empty snapshot";
        }
        SupplierImportProperties.Parser parserCfg = properties.getParser();
        double ratio = result.validRowRatio();
        if (ratio < parserCfg.getFinalMinValidRowRatio()) {
            return String.format(
                    "Valid row ratio %.2f (validRows=%d/totalRows=%d) below threshold %.2f; supplierPrice column may "
                            + "be missing or the layout drifted - this snapshot must not run assortment reconciliation",
                    ratio, result.validRows(), result.totalRows(), parserCfg.getFinalMinValidRowRatio());
        }
        return null;
    }

    private LayoutRuleDefinition parseStoredRule(ImportRuleVersion version) {
        LayoutRuleValidationResult validation = layoutRuleValidator.validate(version.getRuleDefinition());
        if (!validation.valid()) {
            log.error("Stored ACTIVE rule version {} failed re-validation (should be impossible for an "
                    + "immutable, previously-validated rule): {}", version.getId(), validation.errors());
            return null;
        }
        return validation.rule();
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }

    private record RuleResolution(
            LayoutRuleDefinition rule, ImportRuleVersion ruleVersionEntity, String quarantineReason) {

        static RuleResolution reused(LayoutRuleDefinition rule, ImportRuleVersion version) {
            return new RuleResolution(rule, version, null);
        }

        static RuleResolution created(LayoutRuleDefinition rule, ImportRuleVersion version) {
            return new RuleResolution(rule, version, null);
        }

        static RuleResolution quarantined(String reason) {
            return new RuleResolution(null, null, reason);
        }
    }
}
