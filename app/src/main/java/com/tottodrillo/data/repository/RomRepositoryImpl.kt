package com.tottodrillo.data.repository

import android.content.Context
import com.tottodrillo.data.mapper.toDomain
import com.tottodrillo.data.mapper.toRegionInfo
import com.tottodrillo.data.model.EntryResponse
import com.tottodrillo.data.remote.EntryRequestBody
import com.tottodrillo.data.remote.ApiService
import com.tottodrillo.data.remote.NetworkResult
import com.tottodrillo.data.remote.SearchRequestBody
import com.tottodrillo.data.remote.SourceApiAdapter
import com.tottodrillo.data.remote.SourceExecutor
import com.tottodrillo.data.remote.extractData
import com.tottodrillo.data.remote.getUserMessage
import com.tottodrillo.data.remote.safeApiCall
import com.tottodrillo.di.NetworkModule
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.tottodrillo.domain.model.PlatformInfo
import com.tottodrillo.domain.model.RegionInfo
import com.tottodrillo.domain.model.Rom
import com.tottodrillo.domain.model.SearchFilters
import com.tottodrillo.domain.repository.RomRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementazione del RomRepository
 * Gestisce il recupero dati dall'API e dalla cache locale
 */
@Singleton
class RomRepositoryImpl @Inject constructor(
    private val apiService: ApiService?, // Opzionale, deprecato - usa SourceManager invece
    @ApplicationContext private val context: Context,
    private val configRepository: DownloadConfigRepository,
    private val platformManager: com.tottodrillo.domain.manager.PlatformManager,
    private val sourceManager: com.tottodrillo.domain.manager.SourceManager,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val cacheManager: RomCacheManager,
    private val sourceServices: com.tottodrillo.domain.service.SourceServices
) : RomRepository {

    // Cache in memoria per le piattaforme (evita chiamate ripetute)
    private var platformsCache: List<PlatformInfo>? = null
    private var regionsCache: List<RegionInfo>? = null
    
    companion object {
        private const val FAVORITES_FILE_NAME = "tottodrillo-favorites.status"
        private const val RECENT_FILE_NAME = "tottodrillo-recent.status"
        private const val MAX_RECENT_ROMS = 25
    }
    
    /**
     * Ottiene il percorso del file di stato (favoriti o recenti)
     */
    private suspend fun getStatusFilePath(fileName: String): File {
        val config = configRepository.downloadConfig.first()
        return File(config.downloadPath, fileName)
    }

    override suspend fun searchRoms(
        filters: SearchFilters,
        page: Int
    ): NetworkResult<List<Rom>> = withContext(Dispatchers.IO) {
        searchRomsOnIo(filters, page)
    }

    private suspend fun searchRomsOnIo(filters: SearchFilters, page: Int): NetworkResult<List<Rom>> {
        // Verifica se ci sono sorgenti installate
        val hasSources = sourceManager.hasInstalledSources()
        if (!hasSources) {
            return NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    "Nessuna sorgente installata. Installa almeno una sorgente per utilizzare l'app."
                )
            )
        }
        
        return try {
            // Ottieni tutte le sorgenti abilitate
            var enabledSources = sourceManager.getEnabledSources()
            if (enabledSources.isEmpty()) {
                return NetworkResult.Error(
                    com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "Nessuna sorgente abilitata"
                    )
                )
            }
            
            // Filtra le sorgenti in base al filtro selectedSources se presente
            if (filters.selectedSources.isNotEmpty()) {
                enabledSources = enabledSources.filter { it.id in filters.selectedSources }
                if (enabledSources.isEmpty()) {
                    return NetworkResult.Error(
                        com.tottodrillo.data.remote.NetworkException.UnknownError(
                            "Nessuna sorgente selezionata disponibile"
                        )
                    )
                }
            }
            
            val favoriteSlugs = loadFavoritesFromFile().toHashSet()
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
            // Optional remote sources cannot hold an HTTP call forever.
            val searchClient = okHttpClient.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
            val allRoms = coroutineScope {
                enabledSources.map { source ->
                    async {
                        try {
                            val sourceDir = File(source.installPath ?: return@async emptyList())
                            val metadata = sourceManager.getSourceMetadata(source.id)
                                ?: return@async emptyList()
                            
                            val executor = SourceExecutor.create(
                                metadata,
                                sourceDir,
                                searchClient,
                                gson,
                                sourceServices
                            )
                            
                            // Normalizza i codici piattaforma a minuscolo per il mapping corretto
                            val platformsList = filters.selectedPlatforms.takeIf { it.isNotEmpty() }?.map { it.lowercase() } ?: emptyList()
                            
                            val result = withTimeout(15_000L) { executor.searchRoms(
                                searchKey = filters.query.takeIf { it.isNotEmpty() },
                                platforms = platformsList,
                                regions = filters.selectedRegions.takeIf { it.isNotEmpty() } ?: emptyList(),
                                maxResults = 50,
                                page = page
                            ) }
                            
                            result.fold(
                                onSuccess = { searchResults ->
                                    searchResults.results.map { entry ->
                                        entry?.toDomain(sourceId = source.id)
                                    }
                                },
                                onFailure = {
                                    if (it is CancellationException) throw it
                                    failures.add(source.name)
                                    android.util.Log.e("RomRepositoryImpl", "Errore ricerca in sorgente ${source.id}", it)
                                    emptyList()
                                }
                            )
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            failures.add(source.name)
                            emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failures.add(source.name)
                            android.util.Log.e("RomRepositoryImpl", "Errore ricerca in sorgente ${source.id}", e)
                            emptyList()
                        }
                    }
                }.awaitAll()
            }.flatten()
            
            if (allRoms.isEmpty() && failures.isNotEmpty() && failures.size == enabledSources.size) {
                return NetworkResult.Error(com.tottodrillo.data.remote.NetworkException.SourceUnavailable(failures.joinToString(", ")))
            }
            // Filtra ROM nulle e raggruppa per slug
            val validRoms = allRoms.filterNotNull()
            val romsBySlug = validRoms.groupBy { it.slug }
            
            val enrichedRoms = romsBySlug.map { (slug, roms) ->
                // Prendi la prima ROM come base (nome, immagine principale, sourceId)
                val firstRom = roms.first()
                
                // Raccogli tutte le immagini da tutte le ROM
                // coverUrl è la box image (obbligatoria), coverUrls contiene box + screen
                var allCoverUrls = roms
                    .flatMap { rom -> 
                        // coverUrls già contiene box (prima) e screen (dopo) nell'ordine corretto
                        // Filtra null e stringhe vuote
                        rom.coverUrls.filter { it.isNotBlank() }
                    }
                    .distinct()
                    .filter { it.isNotBlank() } // Doppio filtro per sicurezza
                
                allCoverUrls = (allCoverUrls + getPlaceholderImages(roms)).distinct()

                // Unisci tutti i downloadLinks da tutte le ROM
                val allDownloadLinks = roms
                    .flatMap { it.downloadLinks }
                    .distinctBy { it.url } // Rimuovi link duplicati (stesso URL)
                
                // Unisci tutte le regioni
                val allRegions = roms
                    .flatMap { it.regions }
                    .distinctBy { it.code }
                
                // Arricchisci PlatformInfo
                val enrichedPlatform = enrichPlatformInfo(firstRom.platform, firstRom.sourceId)
                
                val finalCoverUrl = allCoverUrls.firstOrNull()
                android.util.Log.d("RomRepositoryImpl", "   🎯 Finale per ${firstRom.title}: coverUrl=$finalCoverUrl, coverUrls=$allCoverUrls")
                
                firstRom.copy(
                    platform = enrichedPlatform,
                    coverUrl = finalCoverUrl, // Prima immagine (box o placeholder)
                    coverUrls = allCoverUrls, // Box/placeholder prima, poi screen
                    downloadLinks = allDownloadLinks,
                    regions = allRegions,
                    isFavorite = slug in favoriteSlugs,
                    sourceId = firstRom.sourceId // SourceId della prima ROM
                )
            }
            
            // Each source owns its pagination; scanning ten extra pages duplicates
            // results, blocks fast sources and hides genuine empty searches.
            val finalRoms = enrichedRoms

            // Filtra ulteriormente per regioni se specificato (doppio controllo)
            // Questo assicura che anche se la sorgente non filtra correttamente, le ROM vengano filtrate
            val finalFilteredRoms = if (filters.selectedRegions.isNotEmpty()) {
                finalRoms.filter { rom ->
                    fun canonical(code: String) = when (code.uppercase()) {
                        "WORLD" -> "WW"
                        "GB" -> "UK"
                        else -> code.uppercase()
                    }
                    rom.regions.any { region ->
                        canonical(region.code) == "WW" ||
                            filters.selectedRegions.any { canonical(it) == canonical(region.code) }
                    }
                }
            } else {
                finalRoms
            }
            
            
            // Ordina alfabeticamente per nome (ignorando maiuscole/minuscole)
            val sortedRoms = finalFilteredRoms.sortedBy { it.title.lowercase() }
            
            NetworkResult.Success(sortedRoms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nella ricerca ROM", e)
            NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    e.message ?: "Errore nella ricerca"
                )
            )
        }
    }

    override suspend fun getPlatforms(): NetworkResult<List<PlatformInfo>> {
        // Ritorna cache se disponibile
        platformsCache?.let { 
            return NetworkResult.Success(it) 
        }

        // Carica le piattaforme da tutte le sorgenti abilitate
        return try {
            val enabledSources = sourceManager.getEnabledSources()
            if (enabledSources.isEmpty()) {
                return NetworkResult.Error(
                    com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "Nessuna sorgente abilitata"
                    )
                )
            }
            
            val allPlatforms = mutableMapOf<String, PlatformInfo>() // Usa mother_code come chiave per evitare duplicati
            
            // Carica le piattaforme solo dalle sorgenti abilitate
            for (source in enabledSources) {
                try {
                    val platforms = platformManager.loadPlatforms(source.id)
                    // Unisci le piattaforme, evitando duplicati per mother_code
                    platforms.forEach { platform ->
                        // Trova il mother_code per questa piattaforma
                        val motherCode = platformManager.getMotherCodeFromSourceCode(platform.code, source.id)
                        if (motherCode != null) {
                            // Se non esiste già o se questa sorgente ha dati migliori, aggiorna
                            if (!allPlatforms.containsKey(motherCode) || 
                                (allPlatforms[motherCode]?.displayName.isNullOrBlank() && !platform.displayName.isNullOrBlank())) {
                                allPlatforms[motherCode] = platform
                            }
                        } else {
                            // Se non troviamo il mother_code, aggiungiamo comunque la piattaforma
                            allPlatforms[platform.code] = platform
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento piattaforme per sorgente ${source.id}", e)
                }
            }
            
            val platformsList = allPlatforms.values.toList()
            platformsCache = platformsList // Salva in cache
            NetworkResult.Success(platformsList)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento piattaforme", e)
            NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    e.message ?: "Errore nel caricamento piattaforme"
                )
            )
        }
    }

    override suspend fun getRegions(): NetworkResult<List<RegionInfo>> {
        // Ritorna cache se disponibile
        regionsCache?.let { 
            return NetworkResult.Success(it) 
        }

        // Verifica se ci sono sorgenti installate
        val hasSources = sourceManager.hasInstalledSources()
        if (!hasSources) {
            return NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    "Nessuna sorgente installata"
                )
            )
        }
        
        return try {
            val enabledSources = sourceManager.getEnabledSources()
            if (enabledSources.isEmpty()) {
                return NetworkResult.Error(
            com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "Nessuna sorgente abilitata"
            )
        )
            }
            
            val allRegions = mutableMapOf<String, RegionInfo>() // Usa codice regione come chiave per evitare duplicati
            
            // Carica le regioni da tutte le sorgenti abilitate
            for (source in enabledSources) {
                try {
                    val sourceDir = File(source.installPath ?: continue)
                    val metadata = sourceManager.getSourceMetadata(source.id) ?: continue
                    
                    val executor = SourceExecutor.create(
                        metadata,
                        sourceDir,
                        okHttpClient,
                        gson
                    )
                    
                    val result = executor.getRegions()
                    result.fold(
                        onSuccess = { regionsResponse ->
                            // Aggiungi le regioni, evitando duplicati
                            // Normalizza i codici (uppercase, trim) per evitare duplicati da sorgenti diverse
                            regionsResponse.regions.forEach { (code, name) ->
                                val normalizedCode = code.trim().uppercase()
                                if (!allRegions.containsKey(normalizedCode)) {
                                    // Crea RegionInfo dal codice normalizzato (usa fromCode per gestire i codici standard)
                                    val regionInfo = RegionInfo.fromCode(normalizedCode)
                                    allRegions[normalizedCode] = regionInfo
                                }
                            }
                        },
                        onFailure = { error ->
                            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento regioni per sorgente ${source.id}", error)
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento regioni per sorgente ${source.id}", e)
                }
            }
            
            val regionsList = allRegions.values.toList()
            regionsCache = regionsList // Salva in cache
            NetworkResult.Success(regionsList)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento regioni", e)
            NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    e.message ?: "Errore nel caricamento regioni"
                )
            )
        }
    }

    override suspend fun getRomsByPlatform(
        platform: String,
        page: Int,
        limit: Int
    ): NetworkResult<List<Rom>> {
        // Usa searchRoms con filtro piattaforma (stesso codice di aggregazione)
        return searchRoms(
            filters = SearchFilters(selectedPlatforms = listOf(platform)),
            page = page
        )
    }

    override suspend fun getRomBySlug(slug: String, includeDownloadLinks: Boolean): NetworkResult<Rom> {
        
        val hasSources = sourceManager.hasInstalledSources()
        if (!hasSources) {
            return NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    "Nessuna sorgente installata"
                )
            )
        }
        
        // PRIMA: Controlla se la ROM è in cache (solo dati, senza link)
        val cachedRom = cacheManager.loadRomFromCache(slug)
        if (cachedRom != null) {
            
            // Se non servono i link, restituisci direttamente dalla cache
            if (!includeDownloadLinks) {
                return NetworkResult.Success(cachedRom)
            }
            
            // Se servono i link, carica solo quelli dalla sorgente e uniscili
            // (i link vengono sempre ricaricati per essere aggiornati)
            val romWithLinks = loadRomWithLinksOnly(slug, cachedRom)
            return romWithLinks
        }
        
        // SECONDO: Se non in cache, carica dalla sorgente
        return try {
            // Cerca in tutte le sorgenti abilitate
            val enabledSources = sourceManager.getEnabledSources()
            if (enabledSources.isEmpty()) {
                return NetworkResult.Error(
                    com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "Nessuna sorgente abilitata"
                    )
                )
            }
            
            
            // Cerca in parallelo in tutte le sorgenti
            val results = coroutineScope {
                enabledSources.map { source ->
                    async {
                        try {
                            
                            val sourceDir = File(source.installPath ?: return@async null)
                            val metadata = sourceManager.getSourceMetadata(source.id)
                                ?: return@async null
                            
                            val executor = SourceExecutor.create(
                                metadata,
                                sourceDir,
                                okHttpClient,
                                gson
                            )
                            
                            val result = executor.getEntry(slug, includeDownloadLinks)
                            result.fold(
                                onSuccess = { entryResponse ->
                                    // Verifica che entry non sia null prima di chiamare toDomain()
                                    entryResponse.entry?.toDomain(sourceId = source.id)
                                },
                                onFailure = {
                                    android.util.Log.e("RomRepositoryImpl", "Errore getEntry in sorgente ${source.id}", it)
                                    null
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("RomRepositoryImpl", "Errore getEntry in sorgente ${source.id}", e)
                            null
                        }
                    }
                }.awaitAll()
            }
            
            // Filtra risultati nulli
            val foundRoms = results.filterNotNull()
            
            if (foundRoms.isEmpty()) {
                return NetworkResult.Error(
                    com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "ROM non trovata in nessuna sorgente"
                    )
                )
            }
            
            // Se ci sono più ROM (da più sorgenti), uniscile
            val firstRom = foundRoms.first()
            
            // Raccogli tutte le immagini da tutte le ROM
            // coverUrl è la box image (obbligatoria), coverUrls contiene box + screen
            var allCoverUrls = foundRoms
                .flatMap { rom -> 
                    // coverUrls già contiene box (prima) e screen (dopo) nell'ordine corretto
                    rom.coverUrls
                }
                .distinct()
            
            allCoverUrls = (allCoverUrls + getPlaceholderImages(foundRoms)).filter { it.isNotBlank() }.distinct()

            // Unisci tutti i downloadLinks da tutte le ROM (solo se richiesto)
            val allDownloadLinks = if (includeDownloadLinks) {
                foundRoms
                    .flatMap { it.downloadLinks }
                    .distinctBy { it.url } // Rimuovi link duplicati (stesso URL)
            } else {
                emptyList() // Non caricare download links per home screen
            }
            
            // Unisci tutte le regioni
            val allRegions = foundRoms
                .flatMap { it.regions }
                .distinctBy { it.code }
            
            // Arricchisci con dati locali
            val enrichedPlatform = enrichPlatformInfo(firstRom.platform, firstRom.sourceId)
            val enrichedRom = firstRom.copy(
                platform = enrichedPlatform,
                coverUrl = allCoverUrls.firstOrNull(), // Prima immagine (box o placeholder)
                coverUrls = allCoverUrls, // Box/placeholder prima, poi screen
                downloadLinks = allDownloadLinks,
                regions = allRegions,
                isFavorite = isFavorite(firstRom.slug),
                sourceId = firstRom.sourceId // SourceId della prima ROM
            )
            
            NetworkResult.Success(enrichedRom)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel recupero ROM", e)
            NetworkResult.Error(
                com.tottodrillo.data.remote.NetworkException.UnknownError(
                    e.message ?: "Errore nel recupero ROM"
                )
            )
        }
    }
    
    /**
     * Carica solo i link di download per una ROM (usato quando la ROM è in cache)
     */
    private suspend fun loadRomWithLinksOnly(slug: String, cachedRom: Rom): NetworkResult<Rom> {
        return try {
            val enabledSources = sourceManager.getEnabledSources()
            if (enabledSources.isEmpty()) {
                return NetworkResult.Error(
                    com.tottodrillo.data.remote.NetworkException.UnknownError(
                        "Nessuna sorgente abilitata"
                    )
                )
            }
            
            // Carica solo i link dalle sorgenti
            val allDownloadLinks = coroutineScope {
                enabledSources.map { source ->
                    async {
                        try {
                            val sourceDir = File(source.installPath ?: return@async emptyList<com.tottodrillo.domain.model.DownloadLink>())
                            val metadata = sourceManager.getSourceMetadata(source.id) ?: return@async emptyList()
                            
                            val executor = SourceExecutor.create(
                                metadata,
                                sourceDir,
                                okHttpClient,
                                gson
                            )
                            
                            val result = executor.getEntry(slug, includeDownloadLinks = true)
                            result.fold(
                                onSuccess = { entryResponse ->
                                    entryResponse.entry?.links?.map { link ->
                                        com.tottodrillo.domain.model.DownloadLink(
                                            name = link.name,
                                            type = link.type,
                                            format = link.format,
                                            url = link.url,
                                            size = link.sizeStr,
                                            sourceId = source.id,
                                            requiresWebView = link.requiresWebView,
                                            delaySeconds = link.delaySeconds,
                                            intermediateUrl = link.intermediateUrl
                                        )
                                    } ?: emptyList()
                                },
                                onFailure = { emptyList() }
                            )
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }.flatten().distinctBy { it.url }
            
            // Unisci la ROM dalla cache con i link aggiornati
            val romWithLinks = cachedRom.copy(downloadLinks = allDownloadLinks)
            NetworkResult.Success(romWithLinks)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore caricamento link per ROM in cache", e)
            NetworkResult.Success(cachedRom) // Restituisci comunque la ROM dalla cache
        }
    }

    override fun getFavoriteRoms(): Flow<List<Rom>> = flow {
        // Carica i favoriti dal file .status
        val favoriteSlugs = loadFavoritesFromFile()
        
        if (favoriteSlugs.isEmpty()) {
        emit(emptyList())
            return@flow
        }
        
        // Carica i dettagli per ogni ROM preferita (senza download links per performance)
        val favoriteRoms = mutableListOf<Rom>()
        for (slug in favoriteSlugs) {
            try {
                when (val result = getRomBySlug(slug, includeDownloadLinks = false)) {
                    is NetworkResult.Success -> {
                        favoriteRoms.add(result.data)
                    }
                    else -> {
                        // Se una ROM non viene trovata (es. eliminata), continua con le altre
                    }
                }
            } catch (e: Exception) {
                // Ignora errori per singole ROM e continua
            }
        }
        
        emit(favoriteRoms)
    }

    override suspend fun addToFavorites(rom: Rom): Result<Unit> {
        return try {
            val favoriteSlugs = loadFavoritesFromFile().toMutableSet()
            favoriteSlugs.add(rom.slug)
            saveFavoritesToFile(favoriteSlugs.toList())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeFromFavorites(romSlug: String): Result<Unit> {
        return try {
            val favoriteSlugs = loadFavoritesFromFile().toMutableSet()
            favoriteSlugs.remove(romSlug)
            saveFavoritesToFile(favoriteSlugs.toList())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isFavorite(romSlug: String): Boolean {
        val favoriteSlugs = loadFavoritesFromFile()
        return favoriteSlugs.contains(romSlug)
    }
    
    override suspend fun trackRomOpened(romSlug: String) {
        try {
            val recentEntries = loadRecentFromFile().toMutableList()
            
            // Rimuovi eventuali duplicati di questo slug
            recentEntries.removeAll { it.first == romSlug }
            
            // Aggiungi in cima con timestamp corrente
            recentEntries.add(0, Pair(romSlug, System.currentTimeMillis()))
            
            // Mantieni solo le ultime MAX_RECENT_ROMS
            val trimmedEntries = recentEntries.take(MAX_RECENT_ROMS)
            
            saveRecentToFile(trimmedEntries)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel tracciamento ROM aperta", e)
        }
    }
    
    override fun getRecentRoms(): Flow<List<Rom>> = flow {
        // Carica le ROM recenti dal file .status
        val recentEntries = loadRecentFromFile()
        
        if (recentEntries.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        
        // Carica i dettagli per ogni ROM recente (senza download links per performance)
        val recentRoms = mutableListOf<Rom>()
        for ((slug, _) in recentEntries) {
            try {
                when (val result = getRomBySlug(slug, includeDownloadLinks = false)) {
                    is NetworkResult.Success -> {
                        recentRoms.add(result.data)
                    }
                    else -> {
                        // Se una ROM non viene trovata (es. eliminata), continua con le altre
                    }
                }
            } catch (e: Exception) {
                // Ignora errori per singole ROM e continua
            }
        }
        
        emit(recentRoms)
    }
    
    override fun getDownloadedRoms(): Flow<List<Rom>> = flow {
        // Carica le ROM scaricate/installate dai file .status (già ordinate per data decrescente)
        val downloadedSlugsWithTimestamp = loadDownloadedRomsFromStatusFiles()
        
        if (downloadedSlugsWithTimestamp.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        
        // Carica i dettagli per ogni ROM scaricata (senza download links per performance)
        // Mantiene l'ordine per timestamp (più recenti per prime)
        val downloadedRoms = mutableListOf<Rom>()
        for ((slug, _) in downloadedSlugsWithTimestamp) {
            try {
                when (val result = getRomBySlug(slug, includeDownloadLinks = false)) {
                    is NetworkResult.Success -> {
                        downloadedRoms.add(result.data)
                    }
                    else -> {
                        // Se una ROM non viene trovata (es. eliminata), continua con le altre
                    }
                }
            } catch (e: Exception) {
                // Ignora errori per singole ROM e continua
            }
        }
        
        emit(downloadedRoms)
    }
    
    /**
     * Carica gli slug delle ROM scaricate/installate dai file .status
     * Legge tutti i file .status nella directory di download e cerca di estrarre lo slug
     * Restituisce una lista di Pair<slug, timestamp> ordinata per data decrescente (più recenti per prime)
     */
    private suspend fun loadDownloadedRomsFromStatusFiles(): List<Pair<String, Long>> {
        return try {
            val config = configRepository.downloadConfig.first()
            val downloadDir = File(config.downloadPath)
            
            if (!downloadDir.exists() || !downloadDir.isDirectory) {
                return emptyList()
            }
            
            // Cerca tutti i file .status (escludendo i file di sistema come favorites e recent)
            val statusFiles = downloadDir.listFiles { _, name -> 
                name.endsWith(".status") && 
                name != FAVORITES_FILE_NAME && 
                name != RECENT_FILE_NAME
            } ?: return emptyList()
            
            val slugToTimestamp = mutableMapOf<String, Long>()
            
            for (statusFile in statusFiles) {
                try {
                    // Usa la data di modifica del file .status come timestamp del download
                    val fileTimestamp = statusFile.lastModified()
                    
                    val lines = statusFile.readLines().filter { it.isNotBlank() }
                    
                    // Cerca lo slug nella prima riga (formato: SLUG:<slug>)
                    var slug: String? = null
                    val urlLines = mutableListOf<String>()
                    
                    for (line in lines) {
                        if (line.startsWith("SLUG:")) {
                            // Estrai lo slug dalla riga speciale
                            slug = line.substringAfter("SLUG:").trim()
                        } else {
                            // Aggiungi le righe URL alla lista
                            urlLines.add(line)
                        }
                    }
                    
                    // Se non c'è uno slug esplicito, prova a estrarlo dall'URL o dal nome del file
                    if (slug == null && urlLines.isNotEmpty()) {
                        val firstUrl = if (urlLines.first().contains('\t')) {
                            urlLines.first().substringBefore('\t').trim()
                        } else {
                            urlLines.first().trim()
                        }
                        slug = extractSlugFromUrlOrFileName(firstUrl, statusFile.name)
                    }
                    
                    if (slug != null) {
                        // Se lo slug esiste già, usa il timestamp più recente
                        val existingTimestamp = slugToTimestamp[slug]
                        if (existingTimestamp == null || fileTimestamp > existingTimestamp) {
                            slugToTimestamp[slug] = fileTimestamp
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RomRepositoryImpl", "Errore lettura file .status: ${statusFile.name}", e)
                }
            }
            
            // Ordina per timestamp decrescente (più recenti per prime) e restituisci solo gli slug
            slugToTimestamp.entries
                .sortedByDescending { it.value }
                .map { Pair(it.key, it.value) }
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento ROM scaricate", e)
            emptyList()
        }
    }
    
    /**
     * Estrae lo slug da un URL o dal nome del file
     * Prova vari pattern comuni per le sorgenti
     */
    private fun extractSlugFromUrlOrFileName(url: String, fileName: String): String? {
        // Pattern comuni per estrarre lo slug da URL di varie sorgenti
        // Prova pattern generico per URL con /vault/{slug}
        val vaultPattern = Regex("/(?:vault|roms)/([^/?]+)")
        vaultPattern.find(url)?.let {
            return it.groupValues[1]
        }
        
        // Prova pattern generico per URL con percorso dopo il dominio
        val genericPattern = Regex("://[^/]+/([^/?]+)")
        genericPattern.find(url)?.let {
            return it.groupValues[1]
        }
        
        // Se non trovato nell'URL, prova a estrarre dal nome del file
        // Rimuovi estensione e .status
        val cleanFileName = fileName
            .removeSuffix(".status")
            .substringBeforeLast(".")
        
        // Se il nome del file sembra uno slug (senza spazi, caratteri speciali limitati), usalo
        if (cleanFileName.matches(Regex("^[a-zA-Z0-9_-]+$")) && cleanFileName.length > 3) {
            return cleanFileName
        }
        
        return null
    }
    
    /**
     * Carica i favoriti dal file .status
     * Formato: una riga per slug
     */
    private suspend fun loadFavoritesFromFile(): List<String> {
        return try {
            val file = getStatusFilePath(FAVORITES_FILE_NAME)
            if (file.exists() && file.isFile) {
                file.readLines().filter { it.isNotBlank() }.map { it.trim() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento favoriti", e)
            emptyList()
        }
    }
    
    /**
     * Salva i favoriti nel file .status
     * Formato: una riga per slug
     */
    private suspend fun saveFavoritesToFile(slugs: List<String>) {
        try {
            val file = getStatusFilePath(FAVORITES_FILE_NAME)
            // Assicura che la directory esista
            file.parentFile?.mkdirs()
            file.writeText(slugs.joinToString("\n"))
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel salvataggio favoriti", e)
            throw e
        }
    }
    
    /**
     * Carica le ROM recenti dal file .status
     * Formato: una riga per entry, formato: <slug>\t<timestamp>
     */
    private suspend fun loadRecentFromFile(): List<Pair<String, Long>> {
        return try {
            val file = getStatusFilePath(RECENT_FILE_NAME)
            if (file.exists() && file.isFile) {
                file.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.contains('\t')) {
                            val parts = trimmed.split('\t', limit = 2)
                            if (parts.size == 2) {
                                val slug = parts[0]
                                val timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                                Pair(slug, timestamp)
                            } else {
                                null
                            }
                        } else {
                            // Formato retrocompatibile: solo slug, usa timestamp corrente
                            Pair(trimmed, System.currentTimeMillis())
                        }
                    }
                    .sortedByDescending { it.second } // Ordina per timestamp decrescente
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel caricamento ROM recenti", e)
            emptyList()
        }
    }
    
    /**
     * Salva le ROM recenti nel file .status
     * Formato: una riga per entry, formato: <slug>\t<timestamp>
     */
    private suspend fun saveRecentToFile(entries: List<Pair<String, Long>>) {
        try {
            val file = getStatusFilePath(RECENT_FILE_NAME)
            // Assicura che la directory esista
            file.parentFile?.mkdirs()
            val content = entries.joinToString("\n") { "${it.first}\t${it.second}" }
            file.writeText(content)
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nel salvataggio ROM recenti", e)
            throw e
        }
    }

    /**
     * Recupera le immagini placeholder per le sorgenti che hanno trovato la ROM
     * Se una ROM non ha immagini, usa le immagini placeholder delle sorgenti che l'hanno trovata
     * Se non ci sono placeholder dalle sorgenti, usa il logo dell'app come ultima spiaggia
     */
    private suspend fun getPlaceholderImages(roms: List<Rom>): List<String> {
        val placeholderUrls = mutableListOf<String>()
        val sourceIds = roms.mapNotNull { it.sourceId }.distinct()
        
        
        for (sourceId in sourceIds) {
            val metadata = sourceManager.getSourceMetadata(sourceId)
            val defaultImage = metadata?.defaultImage
            android.util.Log.d("RomRepositoryImpl", "   Source $sourceId: defaultImage=$defaultImage")
            
            defaultImage?.let { imagePath ->
                // Se è un percorso relativo (es. "switchroms/placeholder.png"), risolvilo come file:// URI
                if (!imagePath.startsWith("http://") && !imagePath.startsWith("https://") && !imagePath.startsWith("android.resource://")) {
                    // Cerca la source installata per ottenere il percorso
                    val installedSources = sourceManager.getInstalledSources()
                    val source = installedSources.find { it.id == sourceId }
                    source?.installPath?.let { installPath ->
                        // Rimuovi il prefisso sourceId/ se presente nel percorso (es. "switchroms/placeholder.png" -> "placeholder.png")
                        val cleanPath = if (imagePath.startsWith("$sourceId/")) {
                            imagePath.removePrefix("$sourceId/")
                        } else {
                            imagePath
                        }
                        val placeholderFile = File(installPath, cleanPath)
                        if (placeholderFile.exists()) {
                            val fileUri = android.net.Uri.fromFile(placeholderFile).toString()
                            placeholderUrls.add(fileUri)
                        } else {
                            android.util.Log.w("RomRepositoryImpl", "   ⚠️ Placeholder non trovato: ${placeholderFile.absolutePath}")
                        }
                    } ?: run {
                        android.util.Log.w("RomRepositoryImpl", "   ⚠️ Source $sourceId non installata o percorso non disponibile")
                    }
                } else {
                    // È già un URL completo, usalo così com'è
                    placeholderUrls.add(imagePath)
                }
            }
        }
        
        // Se non ci sono placeholder dalle sorgenti, usa il logo dell'app come ultima spiaggia
        if (placeholderUrls.isEmpty()) {
            val appLogoUri = "android.resource://${context.packageName}/drawable/duke_placeholder"
            placeholderUrls.add(appLogoUri)
            android.util.Log.d("RomRepositoryImpl", "📱 Usando logo app come placeholder (nessun placeholder dalle sorgenti): $appLogoUri")
        } else {
        }
        
        return placeholderUrls.distinct()
    }
    
    /**
     * Arricchisce PlatformInfo con i dati locali dal PlatformManager
     * Sostituisce completamente i dati con quelli locali (nome, brand, immagine, descrizione)
     * per rendere il sistema indipendente dalla sorgente API
     */
    private suspend fun enrichPlatformInfo(platformInfo: PlatformInfo, sourceId: String? = null): PlatformInfo {
        return try {
            // Il codice in platformInfo potrebbe essere:
            // 1. Un codice sorgente (es. "32x" da CrocDB) -> dobbiamo trovare il mother_code
            // 2. Un mother_code (es. "nes" da Vimm's Lair) -> possiamo usarlo direttamente
            
            // Prova prima a vedere se è un mother_code cercando in tutte le sorgenti
            val installedSources = sourceManager.getInstalledSources()
            var motherCode: String? = null
            
            // Se abbiamo sourceId, prova a trovare il mother_code da quella sorgente
            if (sourceId != null) {
                motherCode = platformManager.getMotherCodeFromSourceCode(platformInfo.code, sourceId)
            }
            
            // Se non trovato, prova in tutte le sorgenti
            if (motherCode == null) {
                for (source in installedSources) {
                    motherCode = platformManager.getMotherCodeFromSourceCode(platformInfo.code, source.id)
                    if (motherCode != null) break
                }
            }
            
            // Se non è un codice sorgente, potrebbe essere già un mother_code
            val finalMotherCode = motherCode ?: platformInfo.code
            
            // Carica platforms_main.json per ottenere i dati della piattaforma
            val platformsMain = platformManager.loadPlatformsMain()
            val motherPlatform = platformsMain.platforms.find { it.motherCode == finalMotherCode }
            
            if (motherPlatform != null) {
                // Usa i dati locali da platforms_main.json
                platformInfo.copy(
                    code = finalMotherCode, // Usa il mother_code
                    displayName = motherPlatform.name ?: motherPlatform.motherCode, // Nome locale
                    manufacturer = motherPlatform.brand, // Brand locale
                    imagePath = motherPlatform.image, // Immagine locale
                    description = motherPlatform.description // Descrizione locale
                )
            } else {
                // Se non trovata in platforms_main.json, mantieni i dati originali
                // Log solo se non è un codice sorgente non mappato (evita spam di warning)
                if (finalMotherCode != platformInfo.code || sourceId != null) {
                    android.util.Log.w("RomRepositoryImpl", "Piattaforma $finalMotherCode non trovata in platforms_main.json (source: $sourceId, original code: ${platformInfo.code})")
                }
                platformInfo
            }
        } catch (e: Exception) {
            android.util.Log.e("RomRepositoryImpl", "Errore nell'arricchimento PlatformInfo", e)
            platformInfo
        }
    }
    
    /**
     * Pulisce la cache (utile per refresh)
     */
    override fun clearCache() {
        platformsCache = null
        regionsCache = null
    }
    
    /**
     * Pulisce la cache ROM (chiamato quando si cancella lo storico)
     */
    suspend fun clearRomCache() {
        cacheManager.clearCache()
    }
    
    /**
     * Pulisce la cache di una ROM specifica (chiamato quando si fa refresh)
     */
    suspend fun clearRomCache(slug: String) {
        cacheManager.clearRomCache(slug)
    }
}
