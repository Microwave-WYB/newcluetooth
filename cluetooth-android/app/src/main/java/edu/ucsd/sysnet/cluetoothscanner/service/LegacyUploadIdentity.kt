package edu.ucsd.sysnet.cluetoothscanner.service

private const val LEGACY_PENDING_SUFFIX = ".jsonl.zst"
private val LEGACY_PENDING_PATTERN = Regex(
    "^[^/]+_0\\.0\\.[1-4](?:[-+][A-Za-z0-9_.-]+)?\\.jsonl\\.zst$",
)

/** Keeps the producer timestamp/device/version encoded by StorageService stable across retries. */
internal fun legacyRemoteObjectName(localFileName: String): String? {
    if (!localFileName.endsWith(LEGACY_PENDING_SUFFIX)) return null
    if (!LEGACY_PENDING_PATTERN.matches(localFileName)) return null
    val identity = localFileName.removeSuffix(LEGACY_PENDING_SUFFIX)
    if (identity.isBlank()) return null
    return "$localFileName.encrypted"
}
