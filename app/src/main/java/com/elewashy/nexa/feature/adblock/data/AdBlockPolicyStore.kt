package com.elewashy.nexa.feature.adblock.data

import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.engine.Hostnames
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Effective filtering switches for one page. */
data class SitePolicy(
    /** Any filtering at all (false: ad blocker off globally or a trusted site). */
    val filtering: Boolean,
    /** Element hiding and scriptlets. */
    val cosmeticFiltering: Boolean,
    /** Pop-up / pop-under blocking. */
    val popupBlocking: Boolean,
) {
    companion object {
        val DEFAULT = SitePolicy(filtering = true, cosmeticFiltering = true, popupBlocking = true)
        val OFF = SitePolicy(filtering = false, cosmeticFiltering = false, popupBlocking = false)
    }
}

/**
 * Immutable snapshot of the global switch and every per-site setting.
 * Lookups walk the hostname's suffixes, so a setting for `example.com`
 * covers `www.example.com` and `cdn.example.com`; the most specific wins.
 */
class AdBlockPolicy internal constructor(
    val globalEnabled: Boolean,
    private val sites: Map<String, SiteAdBlockSettings>,
) {
    /** The stored setting that governs [host] (itself or its closest parent), or null. */
    fun governingSettings(host: String?): SiteAdBlockSettings? {
        if (host.isNullOrEmpty() || sites.isEmpty()) return null
        var start = 0
        while (true) {
            val candidate = if (start == 0) host else host.substring(start)
            sites[candidate]?.let { return it }
            val dot = host.indexOf('.', start)
            if (dot == -1 || dot == host.length - 1) return null
            start = dot + 1
        }
    }

    fun forHost(host: String?): SitePolicy {
        if (!globalEnabled) return SitePolicy.OFF
        val settings = governingSettings(host) ?: return SitePolicy.DEFAULT
        if (!settings.blockingEnabled) return SitePolicy.OFF
        return SitePolicy(
            filtering = true,
            cosmeticFiltering = settings.cosmeticFilteringEnabled,
            popupBlocking = settings.popupBlockingEnabled,
        )
    }

    fun forUrl(url: String?): SitePolicy = forHost(url?.let(Hostnames::hostOf))

    companion object {
        /** Used until the persisted state is loaded: filtering on, no per-site settings. */
        internal val INITIAL = AdBlockPolicy(globalEnabled = true, sites = emptyMap())

        fun of(globalEnabled: Boolean, sites: List<SiteAdBlockSettings>): AdBlockPolicy =
            AdBlockPolicy(globalEnabled, sites.associateBy { it.host })
    }
}

/**
 * Keeps the ad blocker's switches (global + per-site) in memory for the
 * WebView hot path, mirroring Room/DataStore. Reads are a single volatile
 * load plus a few hash lookups per page; nothing touches the database
 * while browsing.
 */
@Singleton
class AdBlockPolicyStore @Inject constructor(
    private val siteSettingsRepository: SiteSettingsRepository,
    private val appPreferences: AppPreferences,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val _policy = MutableStateFlow(AdBlockPolicy.INITIAL)
    private val loaded = CountDownLatch(1)

    /** Latest policy; emits on every change of the global switch or a site setting. */
    val policy: StateFlow<AdBlockPolicy> = _policy.asStateFlow()

    init {
        appScope.launch {
            try {
                combine(appPreferences.adBlockEnabled, siteSettingsRepository.sites, AdBlockPolicy::of)
                    .collect { policy ->
                        _policy.value = policy
                        loaded.countDown()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep filtering with the defaults rather than failing open or blocking requests.
                Log.e(TAG, "Ad-block settings unavailable; using defaults", e)
                loaded.countDown()
            }
        }
    }

    /** The current policy, without waiting. Safe from any thread. */
    fun current(): AdBlockPolicy = _policy.value

    /**
     * The current policy, waiting (bounded) for the persisted state at
     * process start, so a trusted site is never filtered on the first load.
     * Call only from background threads (WebView's `shouldInterceptRequest`).
     */
    fun awaitCurrent(): AdBlockPolicy {
        if (loaded.count > 0L) {
            try {
                loaded.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return _policy.value
    }

    /**
     * Persists [settings] and returns once the in-memory policy reflects
     * them, so a reload issued right after sees the new state.
     */
    suspend fun saveSite(settings: SiteAdBlockSettings) {
        siteSettingsRepository.save(settings)
        awaitApplied(settings.host) { it == settings.takeUnless { s -> s.isDefault } }
    }

    suspend fun removeSite(host: String) {
        siteSettingsRepository.remove(host)
        awaitApplied(host) { it == null }
    }

    private suspend fun awaitApplied(host: String, predicate: (SiteAdBlockSettings?) -> Boolean) {
        withTimeoutOrNull(APPLY_TIMEOUT_MS) {
            policy.first { policy -> predicate(policy.governingSettings(host)?.takeIf { it.host == host }) }
        } ?: Log.w(TAG, "Site setting for $host not reflected within $APPLY_TIMEOUT_MS ms")
    }

    private companion object {
        const val TAG = "AdBlockPolicyStore"
        const val LOAD_TIMEOUT_MS = 2_000L
        const val APPLY_TIMEOUT_MS = 2_000L
    }
}
