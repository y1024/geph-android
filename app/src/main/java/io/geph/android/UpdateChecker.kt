package io.geph.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.vdurmont.semver4j.Semver
import io.geph.android.tun2socks.TunnelManager.Companion.NOTIFICATION_ID
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Class that performs the actual update checking and handling.
 */
class UpdateChecker(
    private val context: Context,
    private val daemonRpc: (String, JSONArray) -> String
) {
    private val TAG = "UpdateChecker"

    /**
     * Checks for updates by querying the daemon for the update manifest.
     */
    fun checkForUpdates() {
        try {
            Log.d(TAG, "Checking for updates...")

            // Call daemon RPC to get update manifest
            val manifestResult = getUpdateManifest()
            if (manifestResult == null) {
                Log.e(TAG, "Failed to retrieve update manifest")
                return
            }

            val (manifest, baseUrl) = manifestResult

            // Check if the manifest contains our track
            if (!manifest.has(TRACK)) {
                Log.d(TAG, "No $TRACK track found in manifest")
                return
            }

            // Parse the manifest entry for our track
            val entry = manifest.getJSONObject(TRACK)
            val manifestVersion = entry.getString("version")
            val manifestSha256 = entry.getString("sha256")
            val manifestFilename = entry.getString("filename")

            // Get current app version
            val currentVersion = context.packageManager.getPackageInfo(
                context.packageName, 0
            ).versionName


            Log.d(TAG, "Current version: $currentVersion, Manifest version: $manifestVersion")

            // Compare versions using semver
            if (compareVersions(manifestVersion, currentVersion) <= 0) {
                Log.d(TAG, "No update needed")
                return
            }

            // Construct download URL
            val downloadUrl = "$baseUrl/$TRACK/$manifestVersion/$manifestFilename"
            Log.d(TAG, "Update available. Downloading from: $downloadUrl")

            // Define download path in cache
            val cacheDir = File(context.cacheDir, "geph5-dl")
            cacheDir.mkdirs()
            val hashPath = File(cacheDir, manifestSha256)
            hashPath.mkdirs()

            val downloadPath = File(hashPath, manifestFilename)

            // Check if we already have this file with matching hash
            val needDownload = !downloadPath.exists() ||
                    calculateSha256(downloadPath) != manifestSha256

            if (needDownload) {
                // Download the file
                Log.d(TAG, "Downloading update to ${downloadPath.absolutePath}")
                downloadFile(downloadUrl, downloadPath)

                // Verify the hash
                val fileHash = calculateSha256(downloadPath)
                if (fileHash != manifestSha256) {
                    Log.e(TAG, "Downloaded file hash mismatch")
                    downloadPath.delete()
                    return
                }
                Log.d(TAG, "download finished from ${downloadPath.absolutePath}")
            } else {
                Log.d(TAG, "Update already downloaded and verified")
            }

            // Show update notification
            showUpdateNotification(manifestVersion, downloadPath)

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates: ${e.message}", e)
        }
    }

    /**
     * Gets the update manifest from the daemon.
     * @return Pair of (Manifest JSONObject, Base URL string) or null if retrieval failed
     */
    private fun getUpdateManifest(): Pair<JSONObject, String>? {
        try {
            val args = JSONArray()
            val response = daemonRpc("get_update_manifest", args)
            Log.d(TAG, "got response: $response")
            val jsonResponse = JSONObject(response).getJSONArray("result")

            val manifest = jsonResponse.getJSONObject(0)
            val baseUrl = jsonResponse.getString(1)
            return Pair(manifest, baseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting update manifest: ${e.message}", e)
        }
        return null
    }

    /**
     * Compares two version strings according to semantic versioning using semver4j.
     * @return positive if v1 > v2, negative if v1 < v2, 0 if equal
     */
    private fun compareVersions(v1: String, v2: String?): Int {
        val semver1 = Semver(v1, Semver.SemverType.NPM)
        val semver2 = Semver(v2, Semver.SemverType.NPM)
        return semver1.compareTo(semver2)
    }

    /**
     * Downloads a file from a URL and saves it to the specified path.
     */
    private fun downloadFile(url: String, destination: File) {
        val connection = URL(url).openConnection()
        connection.connect()

        connection.getInputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(4 * 1024) // 4K buffer
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        }
    }

    /**
     * Calculates the SHA256 hash of a file.
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Shows a notification to the user about the available update.
     */
    private fun showUpdateNotification(version: String, apkFile: File) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Geph Updates"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("update", channelName, importance)
            notificationManager.createNotificationChannel(channel)
        }

        // Determine if system language is Chinese
        val isChineseLocale = Locale.getDefault().language.startsWith("zh")

        // Set notification text based on language
        val title = if (isChineseLocale) {
            "迷雾通更新可用"
        } else {
            "Geph Update Available"
        }

        val description = if (isChineseLocale) {
            "迷雾通新版本可用 ($version)。点击安装。"
        } else {
            "A new version of Geph is available ($version). Tap to install."
        }

        // Create intent for when notification is clicked
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Build and show notification
        val notification = NotificationCompat.Builder(context, "update")
            .setSmallIcon(R.drawable.ic_stat_notification_icon)
            .setContentTitle(title)
            .setContentText(description)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TRACK = "android-stable"
    }
}
