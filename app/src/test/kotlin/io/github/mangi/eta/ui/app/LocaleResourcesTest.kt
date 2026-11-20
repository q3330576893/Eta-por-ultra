package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The published source intentionally exposes only zh-Hans and uses Chinese as its default resource set. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun advertisedLocaleMatchesTheExistingChineseOnlyPackagingPolicy() {
        val names = mutableListOf<String>()
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    names += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                }
            }
        }
        assertEquals(listOf("zh-Hans"), names)
    }

    @Test fun otherSystemLocalesUseTheExistingChineseFallbackWithoutPartialEnglishScreens() {
        for (locale in listOf("zh-CN", "zh-SG", "zh-TW", "zh-HK", "en-US", "fr-FR")) {
            assertEquals("设置", localizedString(locale, R.string.route_settings))
            assertEquals("外观与主题", localizedString(locale, R.string.appearance_title))
            assertEquals("当前设备", localizedString(locale, R.string.capability_current_device))
            assertEquals("Eta 正在运行", localizedString(locale, R.string.execution_title))
            assertEquals("天气预报", localizedString(locale, R.string.weather_settings_title))
        }
    }

    @Test fun technicalLabelsAndQuantityFormattingRemainCorrect() {
        for (locale in listOf("zh-CN", "zh-TW", "en-US", "fr-FR")) {
            assertEquals("Skills", localizedString(locale, R.string.route_skills))
            assertEquals("API Key", localizedString(locale, R.string.speech_api_key))
            assertEquals("1 个模型", localizedQuantity(locale, R.plurals.provider_models_count, 1))
            assertEquals("2 个模型", localizedQuantity(locale, R.plurals.provider_models_count, 2))
            assertEquals("2 项任务正在运行，可返回 Eta 查看进度", localizedQuantity(locale, R.plurals.execution_summary, 2))
        }
    }

    private fun localizedString(languageTag: String, resourceId: Int): String =
        configured(languageTag).resources.getString(resourceId)
    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String =
        configured(languageTag).resources.getQuantityString(resourceId, quantity, quantity)
    private fun configured(languageTag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) })
}
