package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportFile;

/**
 * @param alreadyExisted true, если файл с таким content hash для этого shop/source уже был
 *                        принят ранее и никакие новые записи не создавались (idempotent replay).
 */
public record IngestionResult(ImportFile importFile, ImportBatch importBatch, boolean alreadyExisted) {
}
