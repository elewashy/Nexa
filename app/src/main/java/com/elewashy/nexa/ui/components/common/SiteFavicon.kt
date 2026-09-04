package com.elewashy.nexa.ui.components.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.elewashy.nexa.feature.browser.data.favicon.FaviconRepository
import com.elewashy.nexa.feature.browser.data.favicon.FaviconSource
import com.elewashy.nexa.ui.icons.Globe
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FaviconEntryPoint {
    fun faviconRepository(): FaviconRepository
}

/**
 * Shared favicon renderer with a non-jumping globe fallback. Ephemeral browsing contexts can pass
 * [runtimeBitmap] and disable [allowPersistentLookup] to avoid touching the shared favicon cache.
 */
@Composable
fun SiteFavicon(
    pageUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    runtimeBitmap: Bitmap? = null,
    allowPersistentLookup: Boolean = true,
) {
    val containerModifier = modifier
        .size(size)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)

    if (runtimeBitmap != null && !runtimeBitmap.isRecycled) {
        Image(
            bitmap = runtimeBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = containerModifier,
        )
        return
    }
    if (!allowPersistentLookup) {
        Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
            FaviconFallback(size)
        }
        return
    }

    PersistentSiteFavicon(pageUrl = pageUrl, size = size, modifier = containerModifier)
}

@Composable
private fun PersistentSiteFavicon(pageUrl: String, size: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FaviconEntryPoint::class.java,
        ).faviconRepository()
    }
    val sourceFlow = remember(pageUrl) { repository.observe(pageUrl) }
    val source by sourceFlow.collectAsStateWithLifecycle()

    when (val current = source) {
        is FaviconSource.Local,
        is FaviconSource.Remote -> {
            val model = when (current) {
                is FaviconSource.Local -> current.file
                is FaviconSource.Remote -> current.url
            }
            val cacheKey = when (current) {
                is FaviconSource.Local -> current.cacheKey
                is FaviconSource.Remote -> current.cacheKey
            }
            var loaded by remember(model, cacheKey) { mutableStateOf(false) }
            val request = remember(context, model, cacheKey) {
                ImageRequest.Builder(context)
                    .data(model)
                    .memoryCacheKey("favicon:$cacheKey")
                    .diskCacheKey("favicon:$cacheKey")
                    .build()
            }
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                if (!loaded) FaviconFallback(size)
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { loaded = true },
                    onError = {
                        if (current is FaviconSource.Remote) repository.markRemoteFailed(pageUrl)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        FaviconSource.Loading,
        FaviconSource.Unavailable -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                FaviconFallback(size)
            }
        }
    }
}

@Composable
private fun FaviconFallback(size: Dp) {
    Icon(
        imageVector = Globe,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size * FALLBACK_ICON_FRACTION),
    )
}

private const val FALLBACK_ICON_FRACTION = 0.72f
