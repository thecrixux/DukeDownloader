package com.tottodrillo.domain.manager

import android.content.Context
import com.google.gson.Gson
import com.tottodrillo.domain.model.SourceMetadata
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.zip.ZipFile

/** The two reviewed packages are installed offline, without a remote registry. */
class BundledSourceInstaller(private val context: Context, private val manager: SourceManager) {
    companion object { val ids = setOf("lolroms", "emuparadise_ps2") }

    suspend fun install() {
        val installer = SourceInstaller(context, manager)
        for (name in listOf("lolroms-source.zip", "emuparadise-ps2-source.zip")) {
            val temp = File.createTempFile("bundled-source-", ".zip", context.cacheDir)
            try {
                context.assets.open("default_sources/$name").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                val metadata = ZipFile(temp).use { zip ->
                    val entry = zip.getEntry("source.json") ?: error("Missing source.json")
                    zip.getInputStream(entry).reader().use { Gson().fromJson(it, SourceMetadata::class.java) }
                }
                require(metadata.id in ids)
                val current = manager.getSourceMetadata(metadata.id)
                if (current != null && !manager.isVersionNewer(metadata.version, current.version)) continue
                val wasInstalled = manager.isSourceInstalled(metadata.id)
                installer.installFromZip(temp).getOrThrow()
                if (!wasInstalled) manager.setSourceEnabled(metadata.id, true)
            } catch (e: CancellationException) {
                throw e
            } finally {
                temp.delete()
            }
        }
    }
}
