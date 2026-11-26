package com.example.pocketagent

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.Locale

/**
 * Sovereign Privacy & Storage Audit Center for PocketAgent.
 *
 * Invariants:
 * - 100% offline, zero cloud, zero telemetry.
 * - Hardware audit: programmatically inspects granted and requested permissions
 *   to guarantee that android.permission.INTERNET is never requested or granted.
 * - Storage audit: precisely calculates local disk usage across models,
 *   conversations, memories, notes, and cache.
 * - Sovereign Panic Wipe: provides emergency zero-wipe of all user data.
 */
object PrivacyAuditCenter {

    data class PermissionAudit(
        val hasInternetPermission: Boolean,
        val requestedPermissions: List<String>,
        val grantedPermissions: List<String>,
        val isZeroCloudCompliant: Boolean,
    )

    data class StorageAudit(
        val modelBytes: Long,
        val sessionsBytes: Long,
        val memoriesBytes: Long,
        val notesBytes: Long,
        val cacheBytes: Long,
        val totalBytes: Long,
    ) {
        val modelDisplay: String get() = formatBytes(modelBytes)
        val sessionsDisplay: String get() = formatBytes(sessionsBytes)
        val memoriesDisplay: String get() = formatBytes(memoriesBytes)
        val notesDisplay: String get() = formatBytes(notesBytes)
        val cacheDisplay: String get() = formatBytes(cacheBytes)
        val totalDisplay: String get() = formatBytes(totalBytes)

        companion object {
            fun formatBytes(bytes: Long): String = when {
                bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
                bytes >= 1024L -> "${bytes / 1024} KB"
                else -> "$bytes B"
            }
        }
    }

    /**
     * Inspects package manager to verify absolute zero-telemetry guarantee.
     */
    fun auditPermissions(context: Context): PermissionAudit {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)

        val requested = info.requestedPermissions?.toList() ?: emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)
        val granted = mutableListOf<String>()

        for (i in requested.indices) {
            if ((flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) {
                granted.add(requested[i])
            }
        }

        val hasInternet = requested.any { it.equals("android.permission.INTERNET", ignoreCase = true) }

        return PermissionAudit(
            hasInternetPermission = hasInternet,
            requestedPermissions = requested,
            grantedPermissions = granted,
            isZeroCloudCompliant = !hasInternet,
        )
    }

    /**
     * Computes storage breakdown on local private storage.
     */
    fun auditStorage(filesDir: File, cacheDir: File): StorageAudit {
        val modelFile = File(filesDir, "model.litertlm")
        val whisperFile = File(filesDir, "whisper-model.bin")
        val libraryModelsBytes = getDirectorySize(File(filesDir, "models"))
        val modelBytes = (if (modelFile.exists()) modelFile.length() else 0L) +
                libraryModelsBytes +
                (if (whisperFile.exists()) whisperFile.length() else 0L)

        val sessionsBytes = getDirectorySize(File(filesDir, "sessions"))
        val memoriesBytes = getDirectorySize(File(filesDir, "memories"))
        val notesBytes = getDirectorySize(File(filesDir, "notes"))
        val cacheBytes = getDirectorySize(cacheDir)

        val total = modelBytes + sessionsBytes + memoriesBytes + notesBytes + cacheBytes

        return StorageAudit(
            modelBytes = modelBytes,
            sessionsBytes = sessionsBytes,
            memoriesBytes = memoriesBytes,
            notesBytes = notesBytes,
            cacheBytes = cacheBytes,
            totalBytes = total,
        )
    }

    /**
     * Emergency sovereign panic wipe: deletes all private conversations,
     * memories, notes, cache, and staged/backup artifacts from the device immediately.
     */
    fun wipeAllUserData(filesDir: File, cacheDir: File, purgeModels: Boolean = false): Boolean {
        var success = true
        try {
            File(filesDir, "sessions").deleteRecursively()
            File(filesDir, "memories").deleteRecursively()
            File(filesDir, "notes").deleteRecursively()
            File(filesDir, "projects").deleteRecursively()
            File(filesDir, "artifacts").deleteRecursively()
            File(filesDir, "doc_vault").deleteRecursively()
            cacheDir.listFiles()?.forEach { file ->
                if (!file.deleteRecursively()) success = false
            }

            val artifactNames = listOf(
                "model.litertlm.part",
                "model.litertlm.backup",
                "whisper-model.bin.part",
                "whisper-model.bin.backup",
            )
            artifactNames.forEach { name ->
                val f = File(filesDir, name)
                if (f.exists() && !f.delete()) success = false
            }

            if (purgeModels) {
                File(filesDir, "models").deleteRecursively()
                listOf("model.litertlm", "whisper-model.bin").forEach { name ->
                    val f = File(filesDir, name)
                    if (f.exists() && !f.delete()) success = false
                }
            }
        } catch (_: Exception) {
            success = false
        }
        return success
    }

    private fun getDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirectorySize(file) else file.length()
        }
        return size
    }
}
