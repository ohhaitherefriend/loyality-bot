package com.plstk.loyaltybot.service.importing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Provider-neutral хранилище immutable исходных файлов поставщика. Реализация обязана
 * никогда не перезаписывать уже сохранённый объект: ключ детерминирован по content hash,
 * поэтому повторное сохранение одного и того же файла — no-op, а не overwrite.
 * Production реализация (S3/MinIO) может заменить {@link LocalImportFileStorage} без
 * изменения вызывающего кода — см. docs/ARCHITECTURE.md §4/§6.
 */
public interface ImportFileStorage {

    /**
     * Сохраняет содержимое временного файла под ключом, производным от {@code shopId} и
     * {@code sha256}. Идемпотентно: если объект с таким ключом уже существует, метод не
     * трогает существующие байты и просто возвращает тот же ключ.
     *
     * @param shopId           tenant scope ключа (защита от cross-tenant коллизий имени)
     * @param sha256            hex SHA-256 содержимого, вычисленный вызывающей стороной
     * @param originalFilename  исходное имя файла (используется только для расширения)
     * @param source            путь к уже полностью считанным байтам (например temp file)
     * @return immutable storage key, который можно передать в {@link #open(String)}
     */
    String store(String shopId, String sha256, String originalFilename, Path source) throws IOException;

    InputStream open(String storageKey) throws IOException;

    boolean exists(String storageKey);
}
