package com.plstk.loyaltybot.service.importing;

import java.nio.file.Path;

/** Fully-read attachment bytes on local disk plus their computed SHA-256 hex digest. */
record HashedTempFile(Path path, String sha256Hex, long sizeBytes) {
}
