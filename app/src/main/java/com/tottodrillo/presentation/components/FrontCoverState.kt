package com.tottodrillo.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tottodrillo.data.cache.FrontCoverResolver
import com.tottodrillo.domain.model.Rom
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FrontCoverEntryPoint { fun frontCovers(): FrontCoverResolver }

/** Primary cover is independent of source artwork; source pictures remain in the detail gallery. */
@Composable
fun rememberFrontCoverUrls(rom: Rom, visible: Boolean = true): State<List<String>> {
    val context = LocalContext.current.applicationContext
    val resolver = remember(context) {
        EntryPointAccessors.fromApplication(context, FrontCoverEntryPoint::class.java).frontCovers()
    }
    val placeholder = "android.resource://${context.packageName}/drawable/duke_placeholder"
    val importedCover = rom.coverUrl?.takeIf { !rom.igdbUrl.isNullOrBlank() && it.startsWith("http") }
    val identity = listOf(rom.platform.code, rom.title, rom.regions.map { it.code }.joinToString(","), importedCover, rom.coverUrl)
    return produceState(initialValue = listOf(placeholder), key1 = identity, key2 = visible) {
        value = listOf(placeholder)
        if (visible) {
            val cover = importedCover ?: resolver.resolve(rom)
            value = listOfNotNull(cover, placeholder)
        }
    }
}
