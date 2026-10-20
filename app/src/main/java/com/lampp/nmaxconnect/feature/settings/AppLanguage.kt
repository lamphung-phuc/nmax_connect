package com.lampp.nmaxconnect.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.lampp.nmaxconnect.R

/** The app's languages (res/xml/locales_config.xml); SYSTEM follows the phone. */
enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.settings_option_system),
    ENGLISH("en", R.string.language_english),
    VIETNAMESE("vi", R.string.language_vietnamese),
}

/**
 * Per-app language is a platform feature from Android 13; the system stores it and recreates the activity.
 * shortcut: older phones get no Language row (would need AppCompat's locale storage); add it if they matter.
 */
fun languageSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun currentLanguage(context: Context): AppLanguage {
    if (!languageSupported()) return AppLanguage.SYSTEM
    val tag = context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
    return AppLanguage.entries.find { it.tag == tag && it != AppLanguage.SYSTEM } ?: AppLanguage.SYSTEM
}

fun setLanguage(context: Context, language: AppLanguage) {
    if (!languageSupported()) return
    context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
}
