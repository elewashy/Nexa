package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.data.engine.Hostnames
import com.elewashy.nexa.feature.adblock.data.engine.RegistrableDomainResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Registrable-domain (eTLD+1) lookup backed by the Public Suffix List that
 * ships with OkHttp — the same data browsers use for first/third-party
 * decisions. Wrap in a cache: each lookup parses a URL.
 */
object PublicSuffixDomainResolver : RegistrableDomainResolver {
    override fun registrableDomain(host: String): String? {
        if (host.isEmpty() || Hostnames.isIpAddress(host)) return null
        return "http://$host/".toHttpUrlOrNull()?.topPrivateDomain()
    }
}
