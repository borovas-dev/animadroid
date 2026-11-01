package com.borovas.animadroid.feature.recommend

/**
 * The on-device model: Gemma 4 E2B (instruction tuned), packaged for LiteRT-LM.
 *
 * Verified 2026-10-31 against the Hugging Face API / HTTP HEAD:
 * - Repo: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm (official litert-community org)
 * - File: `gemma-4-E2B-it.litertlm` = 2,588,147,712 bytes (~2.4 GiB), LFS sha256 below
 * - License: apache-2.0, NOT gated (no login / acceptance needed). An optional HF token is still supported for
 *   rate limits / mirrors, and a locally downloaded file can be imported via the system file picker.
 * - Other variants in the repo (-gpu 2.0 GB, -web, vendor NPU builds) are intentionally not used.
 * - Runtime: com.google.ai.edge.litertlm:litertlm-android 0.19.0 (latest on Google Maven at time of writing).
 */
object ModelSpec {
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val URL = "https://huggingface.co/$REPO/resolve/main/$FILE_NAME"
    const val SIZE_BYTES = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    /** Android reports slightly less than the nominal RAM, so "6 GB" devices (~5.5 GiB) must still pass. */
    const val MIN_RAM_BYTES = 5_900_000_000L
    const val FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024

    /** Sanity floor for user-imported files (smaller variants exist, e.g. the 2.0 GB GPU build). */
    const val MIN_IMPORT_BYTES = 500L * 1024 * 1024
}

sealed interface ModelState {
    data object NotInstalled : ModelState

    data class Downloading(val bytes: Long, val total: Long, val phase: Phase) : ModelState {
        enum class Phase { WAITING_FOR_NETWORK, DOWNLOADING, VERIFYING, IMPORTING }

        /** 0..1, or null while indeterminate. */
        val fraction: Float? get() = if (total > 0 && phase != Phase.WAITING_FOR_NETWORK) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class Ready(val sizeBytes: Long) : ModelState
    data class Error(val message: String) : ModelState
}

enum class WorkPhase { NONE, ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** Platform-free view of the download WorkManager job, so the state machine is unit-testable. */
data class DownloadSnapshot(
    val phase: WorkPhase = WorkPhase.NONE,
    val bytes: Long = 0,
    val total: Long = ModelSpec.SIZE_BYTES,
    val verifying: Boolean = false,
    val error: String? = null,
)

data class ImportSnapshot(val bytes: Long, val total: Long)

/** Pure reducer: combines download job, import job and the file on disk into the state shown in the UI. */
fun reduceModelState(
    download: DownloadSnapshot?,
    import: ImportSnapshot?,
    importError: String?,
    modelFileSize: Long?,
): ModelState {
    if (import != null) return ModelState.Downloading(import.bytes, import.total, ModelState.Downloading.Phase.IMPORTING)
    when (download?.phase) {
        WorkPhase.RUNNING -> return ModelState.Downloading(
            download.bytes, download.total,
            if (download.verifying) ModelState.Downloading.Phase.VERIFYING else ModelState.Downloading.Phase.DOWNLOADING,
        )
        WorkPhase.ENQUEUED -> return ModelState.Downloading(
            download.bytes, download.total, ModelState.Downloading.Phase.WAITING_FOR_NETWORK,
        )
        else -> Unit
    }
    if (modelFileSize != null && modelFileSize >= ModelSpec.MIN_IMPORT_BYTES) return ModelState.Ready(modelFileSize)
    if (download?.phase == WorkPhase.FAILED) return ModelState.Error(download.error ?: "Download failed")
    if (importError != null) return ModelState.Error(importError)
    return ModelState.NotInstalled
}

sealed interface RequirementProblem {
    data class NotEnoughRam(val totalBytes: Long) : RequirementProblem
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : RequirementProblem
    /** The LiteRT-LM runtime ships only 64-bit libraries (arm64-v8a, x86_64). */
    data object Unsupported32BitDevice : RequirementProblem
}

/** [alreadyDownloadedBytes] is the size of a resumable partial file, which does not need to be downloaded again. */
fun evaluateRequirements(
    totalRamBytes: Long,
    freeSpaceBytes: Long,
    alreadyDownloadedBytes: Long = 0,
    modelBytes: Long = ModelSpec.SIZE_BYTES,
    supports64Bit: Boolean = true,
): List<RequirementProblem> = buildList {
    if (!supports64Bit) add(RequirementProblem.Unsupported32BitDevice)
    if (totalRamBytes < ModelSpec.MIN_RAM_BYTES) add(RequirementProblem.NotEnoughRam(totalRamBytes))
    val needed = (modelBytes - alreadyDownloadedBytes).coerceAtLeast(0) + ModelSpec.FREE_SPACE_MARGIN_BYTES
    if (freeSpaceBytes < needed) add(RequirementProblem.NotEnoughSpace(needed, freeSpaceBytes))
}

fun RequirementProblem.describe(): String = when (this) {
    is RequirementProblem.NotEnoughRam ->
        "This device has ${"%.1f".format(totalBytes / 1_073_741_824.0)} GB of RAM. The model needs a device with at least 6 GB."
    RequirementProblem.Unsupported32BitDevice ->
        "The on-device model needs a 64-bit device; this one is 32-bit only."
    is RequirementProblem.NotEnoughSpace ->
        "Not enough free storage: ${formatBytes(neededBytes)} needed, ${formatBytes(freeBytes)} available."
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}
