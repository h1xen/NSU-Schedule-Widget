package com.example.scedulealarm.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val changelog: String
)

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val PREFS_NAME = "nsu_schedule_prefs"
    private const val KEY_CUSTOM_UPDATE_URL = "key_custom_update_url"

    // Default update manifest URL on GitHub
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/h1xen/NSU-Schedule-Widget/main/version.json"

    fun getUpdateServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
    }

    fun setUpdateServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_UPDATE_URL, url).apply()
    }

    /**
     * Checks remote server for available app updates on Dispatchers.IO.
     * Returns UpdateInfo if remote versionCode > local versionCode, null if up to date.
     */
    suspend fun checkForUpdate(
        context: Context,
        serverUrl: String = getUpdateServerUrl(context)
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(serverUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NSU-Schedule-Android/${getCurrentVersionCode(context)}")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Сервер обновлений вернул код: $responseCode")
                )
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val updateInfo = Gson().fromJson(json, UpdateInfo::class.java)

            val currentCode = getCurrentVersionCode(context)
            Log.d(TAG, "Current versionCode: $currentCode, Remote versionCode: ${updateInfo.versionCode}")

            if (updateInfo.versionCode > currentCode) {
                Result.success(updateInfo)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed: ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads APK safely to context.cacheDir/updates/, cleans previous files,
     * computes SHA-256 and verifies it against manifest before returning.
     */
    suspend fun downloadApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (updatesDir.exists()) {
            updatesDir.listFiles()?.forEach { it.delete() }
        } else {
            updatesDir.mkdirs()
        }

        val apkFile = File(updatesDir, "nsu_schedule_v${updateInfo.versionCode}.apk")
        var connection: HttpURLConnection? = null

        try {
            val url = URL(updateInfo.apkUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NSU-Schedule-Android/${getCurrentVersionCode(context)}")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Ошибка скачивания APK, код сервера: $responseCode")
                )
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            onProgress(progress.coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }

            // AppSec Verification: Calculate SHA-256 of downloaded file
            val calculatedSha256 = calculateSha256(apkFile)
            val expectedSha256 = updateInfo.sha256.trim()

            Log.d(TAG, "Calculated SHA-256: $calculatedSha256")
            Log.d(TAG, "Expected SHA-256:   $expectedSha256")

            if (!calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                val err = "Ошибка целостности APK! Хэш SHA-256 не совпадает.\n" +
                        "Ожидался: $expectedSha256\nПолучен: $calculatedSha256"
                Log.e(TAG, err)
                return@withContext Result.failure(SecurityException(err))
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            if (apkFile.exists()) {
                apkFile.delete()
            }
            Log.e(TAG, "Download failed: ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Launches standard system APK installation dialog via FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "Cannot install: APK file does not exist")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }
}
