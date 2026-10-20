package com.tottodrillo.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import com.tottodrillo.data.download.SegmentedDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Worker per gestire download di ROM in background
 */
class DownloadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "download_url"
        const val KEY_ORIGINAL_URL = "original_url" // URL originale del link (se diverso dall'URL finale)
        const val KEY_INTERMEDIATE_URL = "intermediate_url" // URL pagina intermedia da visitare per cookie
        const val KEY_DELAY_SECONDS = "delay_seconds" // Secondi da attendere prima del download
        const val KEY_CONNECTIONS = "download_connections"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_COOKIES = "cookies" // Cookie dal WebView per mantenere la sessione (es. Cloudflare)
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TARGET_PATH = "target_path"
        const val KEY_ROM_TITLE = "rom_title"
        const val KEY_TASK_ID = "task_id"
        
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_MAX = "progress_max"
        const val PROGRESS_PERCENTAGE = "progress_percentage"
        
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID_BASE = 1001 // ID base, ogni download aggiunge un offset univoco
        
        private const val BUFFER_SIZE = 8192
    }
    
    // ID notifica univoco per questo worker (basato sul workId)
    private val notificationId: Int by lazy {
        // Usa l'hashCode del workId per generare un ID univoco
        // Assicuriamoci che sia positivo e non conflitti con altri ID
        (NOTIFICATION_ID_BASE + (id.hashCode() and 0x7FFFFFFF) % 1000).toInt()
    }

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Cookie access is shared by the range connections.
    private val cookieJar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
            incoming.forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                cookies.add(cookie)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val originalUrl = inputData.getString(KEY_ORIGINAL_URL) // URL originale (opzionale, per download da WebView)
        val intermediateUrl = inputData.getString(KEY_INTERMEDIATE_URL) // URL pagina intermedia (opzionale, per cookie)
        val delaySeconds = inputData.getInt(KEY_DELAY_SECONDS, 0) // Secondi da attendere (0 se non presente)
        val cookies = inputData.getString(KEY_COOKIES) // Cookie dal WebView (opzionale, per mantenere sessione)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return@withContext Result.failure()
        val romTitle = inputData.getString(KEY_ROM_TITLE) ?: "ROM"
        val romSlug = inputData.getString("rom_slug")
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()

        createNotificationChannel()

        try {
            // Set foreground service
            setForeground(createForegroundInfo(romTitle, 0, romSlug, id))

            // Esegui download
            val outputFile = File(targetPath, fileName)
            
            downloadFile(url, outputFile, romTitle, romSlug, intermediateUrl, delaySeconds, cookies, originalUrl)

            // Crea/aggiorna file .status per confermare il download completato
            // Formato multi-riga:
            // Prima riga (opzionale): SLUG:<slug>
            // Righe successive: <URL> o <URL>\t<PATH_ESTRAZIONE>
            try {
                val statusFile = File(targetPath, "$fileName.status")
                
                // Leggi le righe esistenti (se il file esiste)
                val existingLines = if (statusFile.exists()) {
                    statusFile.readLines().filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                
                // Estrai lo slug esistente (se presente) e le righe URL
                val existingSlug = existingLines.firstOrNull { it.startsWith("SLUG:") }?.substringAfter("SLUG:")?.trim()
                val urlLines = existingLines.filterNot { it.startsWith("SLUG:") }
                
                // Usa lo slug passato o quello esistente
                val slugToSave = romSlug?.takeIf { it.isNotEmpty() } ?: existingSlug
                
                // Verifica se l'URL (o l'URL originale) esiste già nelle righe
                val urlExists = urlLines.any { line ->
                    val existingUrl = if (line.contains('\t')) {
                        line.substringBefore('\t')
                    } else {
                        line.trim()
                    }
                    existingUrl == url || (originalUrl != null && existingUrl == originalUrl)
                }
                
                val updatedUrlLines = urlLines.toMutableList()
                if (!urlExists) {
                    // Aggiungi una nuova riga per questo URL
                    updatedUrlLines.add(url)
                    
                    // Se c'è un URL originale diverso, aggiungilo anche al file .status
                    // Questo permette di trovare il download anche quando si cerca con l'URL originale
                    if (originalUrl != null && originalUrl != url) {
                        val originalUrlExists = urlLines.any { line ->
                            val existingUrl = if (line.contains('\t')) {
                                line.substringBefore('\t')
                            } else {
                                line.trim()
                            }
                            existingUrl == originalUrl
                        }
                        
                        if (!originalUrlExists) {
                            updatedUrlLines.add(originalUrl)
                        }
                    }
                } else {
                }
                
                // Costruisci il contenuto finale: slug (se presente) seguito dalle righe URL
                val finalContent = buildString {
                    if (slugToSave != null) {
                        appendLine("SLUG:$slugToSave")
                    }
                    updatedUrlLines.forEach { line ->
                        appendLine(line)
                    }
                }.trimEnd()
                
                statusFile.writeText(finalContent)
            } catch (e: Exception) {
                Log.e("DownloadWorker", "Errore nella creazione/aggiornamento del file .status", e)
            }

            // Success
            showCompletedNotification(romTitle, outputFile.absolutePath, romSlug)
            
            Result.success(workDataOf(
                "file_path" to outputFile.absolutePath,
                "file_size" to outputFile.length()
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showErrorNotification(romTitle, e.message ?: appContext.getString(com.tottodrillo.R.string.error_unknown), romSlug)
            Result.failure(workDataOf("error" to e.message))
        }
    }

    /**
     * Scarica il file e aggiorna il progresso
     */
    private suspend fun downloadFile(
        url: String, 
        outputFile: File, 
        romTitle: String, 
        romSlug: String?,
        intermediateUrl: String? = null,
        delaySeconds: Int = 0,
        cookies: String? = null, // Cookie dal WebView per mantenere la sessione
        originalUrl: String? = null // URL originale della pagina intermedia (per Referer)
    ) {
        val sourceUrl = inputData.getString(KEY_SOURCE_URL)
        val referer = originalUrl?.takeIf { it.isNotBlank() }
            ?: intermediateUrl?.takeIf { it.isNotBlank() }
            ?: sourceUrl?.takeIf { it.isNotBlank() }
            ?: java.net.URL(url).let { "${it.protocol}://${it.host}/" }
        val agent = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        if (!intermediateUrl.isNullOrBlank()) {
            val intermediate = Request.Builder().url(intermediateUrl)
                .header("User-Agent", agent).header("Referer", referer).build()
            okHttpClient.newCall(intermediate).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            }
        }
        if (delaySeconds > 0) kotlinx.coroutines.delay(delaySeconds.toLong() * 1000)
        val request = Request.Builder().url(url).header("User-Agent", agent)
            .header("Accept", "*/*").header("Referer", referer)
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }.build()
        val connections = inputData.getInt(KEY_CONNECTIONS, 4).let { if (it in setOf(1, 2, 4)) it else 4 }
        SegmentedDownloader(okHttpClient).download(request, outputFile, connections) { current, maximum ->
            val percentage = if (maximum > 0) ((current.toDouble() / maximum) * 100).toInt().coerceIn(0, 100) else 0
            setProgress(workDataOf(PROGRESS_CURRENT to current, PROGRESS_MAX to maximum, PROGRESS_PERCENTAGE to percentage))
            setForeground(createForegroundInfo(romTitle, percentage, romSlug, id))
        }
    }

    /**
     * Crea notification channel per Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                appContext.getString(com.tottodrillo.R.string.downloads_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(com.tottodrillo.R.string.download_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Crea ForegroundInfo per il servizio
     */
    private fun createForegroundInfo(romTitle: String, progress: Int, romSlug: String? = null, workId: java.util.UUID = id): ForegroundInfo {
        // Crea Intent per l'azione "Interrompi download"
        val cancelIntent = Intent(appContext, com.tottodrillo.data.receiver.DownloadCancelReceiver::class.java).apply {
            action = com.tottodrillo.data.receiver.DownloadCancelReceiver.ACTION_CANCEL_DOWNLOAD
            putExtra(com.tottodrillo.data.receiver.DownloadCancelReceiver.EXTRA_WORK_ID, workId.toString())
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(appContext.getString(com.tottodrillo.R.string.notif_download_progress))
            .setContentText(romTitle)
            .setSmallIcon(com.tottodrillo.R.drawable.ic_notification)
            .setContentIntent(createPendingIntent(romSlug))
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(com.tottodrillo.R.string.notif_cancel_download),
                cancelPendingIntent
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ richiede un tipo esplicito per i Foreground Service
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Mostra notifica completamento
     */
    private fun showCompletedNotification(romTitle: String, filePath: String, romSlug: String?) {
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(appContext.getString(com.tottodrillo.R.string.notif_download_completed))
            .setContentText(romTitle)
            .setSmallIcon(com.tottodrillo.R.drawable.ic_notification)
            .setContentIntent(createPendingIntent(romSlug))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId + 1, notification)
    }

    /**
     * Mostra notifica errore
     */
    private fun showErrorNotification(romTitle: String, error: String, romSlug: String?) {
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(appContext.getString(com.tottodrillo.R.string.notif_download_failed))
            .setContentText("$romTitle: $error")
            .setSmallIcon(com.tottodrillo.R.drawable.ic_notification)
            .setContentIntent(createPendingIntent(romSlug))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId + 2, notification)
    }

    /**
     * Crea un PendingIntent per aprire l'app alla schermata della ROM
     * Usa un request code univoco basato sul workId per evitare conflitti tra download simultanei
     */
    private fun createPendingIntent(romSlug: String?): PendingIntent {
        val intent = Intent(appContext, com.tottodrillo.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (romSlug != null) {
                putExtra("romSlug", romSlug)
                action = "OPEN_ROM_DETAIL"
            }
            // Aggiungi anche il workId come extra per identificare univocamente il download
            putExtra("workId", id.toString())
        }
        
        // Usa un request code univoco basato sul workId per evitare conflitti
        // Converte l'UUID in un Int positivo usando hashCode
        val requestCode = (id.hashCode() and 0x7FFFFFFF).toInt()
        
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
