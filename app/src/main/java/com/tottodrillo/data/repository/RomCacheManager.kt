package com.tottodrillo.data.repository

import android.content.Context
import com.google.gson.Gson
import com.tottodrillo.domain.model.Rom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager per gestire la cache su disco dei dati ROM
 * Salva dati ROM (senza link) e immagini nella cartella cache
 */
@Singleton
class RomCacheManager @Inject constructor(
    private val context: Context,
    private val configRepository: DownloadConfigRepository,
    private val gson: Gson
) {
    companion object {
        private const val CACHE_DIR_NAME = "cache"
        private const val IMAGES_DIR_NAME = "images"
        private const val DATA_DIR_NAME = "data"
    }
    
    /**
     * Ottiene la directory della cache
     */
    private suspend fun getCacheDirectory(): File {
        val config = configRepository.downloadConfig.first()
        val cacheDir = File(config.downloadPath, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * Ottiene la directory per i dati ROM (JSON)
     */
    private suspend fun getDataCacheDirectory(): File {
        val cacheDir = getCacheDirectory()
        val dataDir = File(cacheDir, DATA_DIR_NAME)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }
    
    /**
     * Ottiene la directory per le immagini
     */
    private suspend fun getImagesCacheDirectory(): File {
        val cacheDir = getCacheDirectory()
        val imagesDir = File(cacheDir, IMAGES_DIR_NAME)
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        return imagesDir
    }
    
    private fun safeCacheName(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Salva una ROM in cache (senza download links)
     */
    suspend fun saveRomToCache(rom: Rom) = withContext(Dispatchers.IO) {
        try {
            val dataDir = getDataCacheDirectory()
            val cacheFile = File(dataDir, "${safeCacheName(rom.slug)}.json")
            
            // Crea una copia della ROM senza download links
            val romWithoutLinks = rom.copy(downloadLinks = emptyList())
            
            // Salva in JSON
            val json = gson.toJson(romWithoutLinks)
            val temp = File.createTempFile("rom-cache-", ".tmp", dataDir)
            try {
                temp.writeText(json)
                java.nio.file.Files.move(temp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            
        } catch (e: Exception) {
            android.util.Log.e("RomCacheManager", "❌ Errore salvataggio ROM in cache", e)
        }
    }
    
    /**
     * Carica una ROM dalla cache
     */
    suspend fun loadRomFromCache(slug: String): Rom? = withContext(Dispatchers.IO) {
        try {
            val dataDir = getDataCacheDirectory()
            val cacheFile = File(dataDir, "${safeCacheName(slug)}.json")
            
            if (!cacheFile.exists()) {
                return@withContext null
            }
            
            val json = cacheFile.readText()
            val rom = gson.fromJson(json, Rom::class.java)
            
            return@withContext rom
        } catch (e: Exception) {
            android.util.Log.e("RomCacheManager", "❌ Errore caricamento ROM da cache", e)
            null
        }
    }
    
    /**
     * Salva un'immagine in cache
     * @param url URL originale dell'immagine
     * @param imageBytes Byte array dell'immagine
     * @return Percorso locale dell'immagine salvata, o null se errore
     */
    suspend fun saveImageToCache(url: String, imageBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val imagesDir = getImagesCacheDirectory()
            
            // Crea un nome file sicuro dall'URL
            val fileName = safeCacheName(url)
            
            val imageFile = File(imagesDir, fileName)
            val temp = File.createTempFile("image-cache-", ".tmp", imagesDir)
            try {
                temp.writeBytes(imageBytes)
                java.nio.file.Files.move(temp.toPath(), imageFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            
            // Restituisci il percorso locale (file://)
            val localPath = "file://${imageFile.absolutePath}"
            return@withContext localPath
        } catch (e: Exception) {
            android.util.Log.e("RomCacheManager", "❌ Errore salvataggio immagine in cache", e)
            null
        }
    }
    
    /**
     * Verifica se un'immagine è in cache
     * @param url URL originale dell'immagine
     * @return Percorso locale dell'immagine se presente, null altrimenti
     */
    suspend fun getCachedImagePath(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val imagesDir = getImagesCacheDirectory()
            
            // Cerca il file con lo stesso hash
            val fileName = safeCacheName(url)
            
            val imageFile = File(imagesDir, fileName)
            if (imageFile.isFile && imageFile.length() > 0) {
                return@withContext "file://${imageFile.absolutePath}"
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Cancella tutta la cache
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDirectory()
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (e: Exception) {
            android.util.Log.e("RomCacheManager", "❌ Errore cancellazione cache", e)
        }
        Unit
    }
    
    /**
     * Cancella la cache di una ROM specifica
     */
    suspend fun clearRomCache(slug: String) = withContext(Dispatchers.IO) {
        try {
            val dataDir = getDataCacheDirectory()
            val cacheFile = File(dataDir, "${safeCacheName(slug)}.json")
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("RomCacheManager", "❌ Errore cancellazione cache ROM", e)
        }
        Unit
    }
}

