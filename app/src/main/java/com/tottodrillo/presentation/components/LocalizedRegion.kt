package com.tottodrillo.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.tottodrillo.R
import com.tottodrillo.domain.model.RegionInfo
import java.util.Locale

@Composable
fun RegionInfo.localizedDisplayName(): String {
    if (code == "WW" || code == "WORLD") return stringResource(R.string.region_worldwide)
    val country = if (code == "UK") "GB" else code
    val locale = LocalConfiguration.current.locales[0]
    val translated = Locale("", country).getDisplayCountry(locale)
    return translated.takeIf { it.isNotBlank() && it != country } ?: displayName
}
