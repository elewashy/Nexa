package com.elewashy.nexa.feature.browser.data.adblock.engine

/**
 * Names of the scriptlets implemented by the JS files in
 * `assets/adblock/scriptlets/`, with the uBO short aliases and AdGuard
 * equivalents that map onto them.
 * `AdBlockAssetsTest` keeps this catalog and the JS registrations in sync.
 *
 * Trusted scriptlets can run arbitrary-looking logic (rewrite responses, set
 * arbitrary values, click elements). As in uBO they are honoured only from
 * trusted lists (uBO's own lists and Nexa's lists); third-party lists can
 * only use the safe set.
 */
object ScriptletCatalog {

    private class Entry(val name: String, val trusted: Boolean, vararg val aliases: String)

    private val ENTRIES = listOf(
        Entry("set-constant", false, "set", "set-constant.js"),
        Entry("trusted-set-constant", true, "trusted-set"),
        Entry("abort-on-property-read", false, "aopr"),
        Entry("abort-on-property-write", false, "aopw"),
        Entry("abort-current-script", false, "acs", "abort-current-inline-script", "acis"),
        Entry("abort-on-stack-trace", false, "aost"),
        Entry("no-window-open-if", false, "nowoif", "prevent-window-open", "window.open-defuser"),
        Entry("no-setTimeout-if", false, "nostif", "prevent-setTimeout", "setTimeout-defuser"),
        Entry("no-setInterval-if", false, "nosiif", "prevent-setInterval", "setInterval-defuser"),
        Entry("no-requestAnimationFrame-if", false, "norafif", "prevent-requestAnimationFrame"),
        Entry("adjust-setTimeout", false, "nano-stb", "nano-setTimeout-booster"),
        Entry("adjust-setInterval", false, "nano-sib", "nano-setInterval-booster"),
        Entry("addEventListener-defuser", false, "aeld", "prevent-addEventListener"),
        Entry("no-fetch-if", false, "prevent-fetch"),
        Entry("trusted-prevent-fetch", true),
        Entry("no-xhr-if", false, "prevent-xhr"),
        Entry("trusted-prevent-xhr", true),
        Entry("noeval-if", false, "prevent-eval-if"),
        Entry("noeval", false, "silent-noeval"),
        Entry("json-prune", false),
        Entry("json-prune-fetch-response", false),
        Entry("json-prune-xhr-response", false),
        Entry("xml-prune", false),
        Entry("m3u-prune", false),
        Entry("mpegdash-prune", false),
        Entry("json-edit", false),
        Entry("trusted-json-edit", true),
        Entry("json-edit-fetch-response", false),
        Entry("trusted-json-edit-fetch-response", true),
        Entry("json-edit-xhr-response", false),
        Entry("trusted-json-edit-xhr-response", true),
        Entry("json-edit-fetch-request", false),
        Entry("trusted-json-edit-fetch-request", true),
        Entry("json-edit-xhr-request", false),
        Entry("trusted-json-edit-xhr-request", true),
        Entry("jsonl-edit-fetch-response", false),
        Entry("trusted-jsonl-edit-fetch-response", true),
        Entry("jsonl-edit-xhr-response", false),
        Entry("trusted-jsonl-edit-xhr-response", true),
        Entry("edit-inbound-object", false),
        Entry("trusted-edit-inbound-object", true),
        Entry("edit-outbound-object", false),
        Entry("trusted-edit-outbound-object", true),
        Entry("edit-object-on-setter", false),
        Entry("trusted-edit-object-on-setter", true),
        Entry("remove-node-text", false, "rmnt"),
        Entry("replace-node-text", true, "rpnt", "trusted-replace-node-text", "trusted-rpnt"),
        Entry("remove-attr", false, "ra"),
        Entry("remove-class", false, "rc"),
        Entry("set-attr", false),
        Entry("trusted-set-attr", true),
        Entry("set-cookie", false),
        Entry("trusted-set-cookie", true),
        Entry("trusted-set-cookie-reload", true),
        Entry("remove-cookie", false, "cookie-remover"),
        Entry("set-local-storage-item", false),
        Entry("set-session-storage-item", false),
        Entry("trusted-set-local-storage-item", true),
        Entry("trusted-set-session-storage-item", true),
        Entry("nowebrtc", false),
        Entry("href-sanitizer", false),
        Entry("refresh-defuser", false, "prevent-refresh"),
        Entry("disable-newtab-links", false),
        Entry("bab-defuser", false, "nobab"),
        Entry("fuckadblock-defuser", false, "nofab"),
        Entry("popads-dummy", false),
        Entry("trusted-replace-fetch-response", true, "trusted-rpfr"),
        Entry("trusted-replace-xhr-response", true, "trusted-rpxr"),
        Entry("trusted-replace-argument", true),
        Entry("trusted-replace-outbound-text", true),
        Entry("trusted-click-element", true),
        Entry("trusted-create-html", true),
        Entry("trusted-prevent-dom-bypass", true),
        Entry("trusted-suppress-native-method", true),
        Entry("trusted-override-element-method", true),
        Entry("spoof-css", false),
        Entry("prevent-innerHTML", false),
        Entry("prevent-navigation", false),
        Entry("window-close-if", false, "close-window"),
        Entry("alert-buster", false),
        Entry("prevent-canvas", false),
        Entry("prevent-clipboard-write", false),
        Entry("proxy-apply-config", false),
        // uBO resources that filters inject as scriptlets (`##+js(popads.net)`).
        Entry("popads.net", false),
        Entry("fingerprint2", false),
        Entry("multiup", false),
    )

    private val byAlias: Map<String, Entry> = buildMap {
        for (entry in ENTRIES) {
            put(entry.name, entry)
            for (alias in entry.aliases) put(alias, entry)
        }
    }

    /** Canonical scriptlet names (each must be implemented by the JS library). */
    val canonicalNames: List<String> = ENTRIES.map { it.name }

    /** Canonical name for [name] regardless of trust; unknown names are returned unchanged. */
    fun canonicalName(name: String): String = byAlias[name]?.name ?: name

    /**
     * Resolves [name] (alias or canonical) to the canonical name, or null when
     * unknown or when a trusted scriptlet is requested by an untrusted list.
     */
    fun resolve(name: String, trustedSource: Boolean): String? {
        val entry = byAlias[name] ?: return null
        if (entry.trusted && !trustedSource) return null
        return entry.name
    }
}
