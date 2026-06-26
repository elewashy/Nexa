package com.elewashy.nexa.core.localization

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.elewashy.nexa.R

enum class AppLanguage(
    val tag: String?,
    @param:StringRes val labelRes: Int,
    val nativeName: String?,
) {
    System(null, R.string.language_system_default, null),
    English("en", R.string.language_english, "English"),
    Arabic("ar", R.string.language_arabic, "العربية"),
    French("fr", R.string.language_french, "Français"),
}

object AppLanguageManager {
    val supportedLanguages: List<AppLanguage> = AppLanguage.entries
    val selectableLanguages: List<AppLanguage> = listOf(AppLanguage.English, AppLanguage.Arabic, AppLanguage.French)

    fun currentLanguage(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AppLanguage.System

        val currentTag = locales[0]?.language ?: return AppLanguage.System
        return supportedLanguages.firstOrNull { it.tag == currentTag } ?: AppLanguage.System
    }

    fun setLanguage(language: AppLanguage) {
        setLanguageTag(language.tag)
    }

    fun setLanguageTag(tag: String?) {
        val locales = tag?.let(LocaleListCompat::forLanguageTags)
            ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun fromTag(tag: String?): AppLanguage =
        supportedLanguages.firstOrNull { it.tag == tag } ?: AppLanguage.System
}
