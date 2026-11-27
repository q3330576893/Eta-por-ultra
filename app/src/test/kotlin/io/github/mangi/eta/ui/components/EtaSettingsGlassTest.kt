package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaSettingsGlassTest {
    @Test
    fun settingsMaterialDoesNotLeakOutsideItsPageAndNestedPagesReuseTheirPalette() {
        val original = Modifier.size(120.dp)
        var before: Modifier? = null
        var after: Modifier? = null
        var outsideBefore = true
        var outsideAfter = true
        var inside = false
        var nested = false
        var outerPalette: EtaSettingsGlassPalette? = null
        var nestedPalette: EtaSettingsGlassPalette? = null
        SettingsComposeTestHost(content = {
            val beforeModifier = original.etaSettingsGlass()
            val beforeActive = LocalSettingsGlassActive.current
            SideEffect { before = beforeModifier; outsideBefore = beforeActive }
            EtaSettingsGlassPage {
                val active = LocalSettingsGlassActive.current
                val palette = etaSettingsGlassPalette()
                SideEffect { inside = active; outerPalette = palette }
                EtaSettingsGlassPage {
                    val nestedActive = LocalSettingsGlassActive.current
                    val sharedPalette = etaSettingsGlassPalette()
                    SideEffect { nested = nestedActive; nestedPalette = sharedPalette }
                    Box(original.etaSettingsGlass())
                }
            }
            val afterModifier = original.etaSettingsGlass()
            val afterActive = LocalSettingsGlassActive.current
            SideEffect { after = afterModifier; outsideAfter = afterActive }
        }).use {
            assertFalse(outsideBefore)
            assertFalse(outsideAfter)
            assertTrue(inside)
            assertTrue(nested)
            assertSame("Shared chat modifiers must be untouched outside settings", original, before)
            assertSame(original, after)
            assertSame("Nested pages must reuse the settings material context", outerPalette, nestedPalette)
        }
    }

    @Test
    fun changingBlurPreferenceReplacesSampledMaterialWithFallbackWithoutLosingContent() {
        assertTrue(isRuntimeShaderSupported())
        val enabled = mutableStateOf(false)
        var snapshot: MaterialSnapshot? = null
        SettingsComposeTestHost(content = {
            CompositionLocalProvider(LocalBlurEnabled provides enabled.value) {
                EtaSettingsGlassPage {
                    val material = Modifier.etaSettingsGlass()
                    val current = MaterialSnapshot(LocalSettingsGlassActive.current, material, etaSettingsGlassPalette())
                    SideEffect { snapshot = current }
                    Box(Modifier.size(240.dp, 120.dp).then(material)) { Text("Readable settings") }
                }
            }
        }).use { host ->
            assertFallback(requireNotNull(snapshot))
            host.captureIfRequested("settings_blur_off_light")
            enabled.value = true
            host.advance(8)
            assertTrue(requireNotNull(snapshot).active)
            assertTrue("Enabled blur must sample the dedicated miuix backdrop", requireNotNull(snapshot).modifier.hasBlurElement())
            assertTrue(host.nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Readable settings" } == true
            })
            host.captureIfRequested("settings_blur_on_light")
            enabled.value = false
            host.advance(8)
            assertFallback(requireNotNull(snapshot))
        }
    }

    @Test
    fun blurDisabledUsesHighContrastFallbackInBothColorSchemes() {
        for (dark in listOf(false, true)) {
            var snapshot: MaterialSnapshot? = null
            SettingsComposeTestHost(
                appearance = AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    blurEnabled = false,
                ),
                content = {
                    EtaSettingsGlassPage {
                        val material = Modifier.etaSettingsGlass(emphasized = true)
                        val current = MaterialSnapshot(LocalSettingsGlassActive.current, material, etaSettingsGlassPalette())
                        SideEffect { snapshot = current }
                        Box(Modifier.size(240.dp, 160.dp).then(material)) {
                            Text("Settings fallback", color = current.palette.onSurface)
                        }
                    }
                },
            ).use { host ->
                val actual = requireNotNull(snapshot)
                assertFallback(actual)
                val background = actual.palette.fallbackSurface.compositeOver(actual.palette.background)
                val foreground = actual.palette.onSurface.compositeOver(background)
                val contrast = (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
                    (minOf(foreground.luminance(), background.luminance()) + 0.05f)
                assertTrue("Fallback body text must retain 4.5:1 contrast; dark=$dark, ratio=$contrast", contrast >= 4.5f)
                assertEquals(dark, background.luminance() < 0.5f)
                val textNode = host.nodes().single { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Settings fallback" } == true
                }
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(textNode.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                assertEquals(actual.palette.onSurface, layouts.single().layoutInput.style.color)
                val bitmap = host.captureBitmap()
                try {
                    val surface = Color(bitmap.getPixel(host.dp(100f).toInt(), host.dp(100f).toInt())).luminance()
                    val bounds = textNode.boundsInRoot
                    var renderedContrast = 1f
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                            val pixel = Color(bitmap.getPixel(x, y)).luminance()
                            renderedContrast = maxOf(renderedContrast, (maxOf(pixel, surface) + 0.05f) / (minOf(pixel, surface) + 0.05f))
                        }
                    }
                    assertTrue("Rendered glyphs must contrast with the actual fallback; dark=$dark, ratio=$renderedContrast", renderedContrast >= 4.5f)
                } finally {
                    bitmap.recycle()
                }
                host.captureIfRequested(if (dark) "settings_fallback_dark" else "settings_fallback_light")
            }
        }
    }

    @Test
    fun unavailableRuntimeShaderUsesFallbackEvenWhenBlurPreferenceIsEnabled() {
        // miuix 0.9.4 reports shader support solely from SDK_INT >= 33. Override that capability
        // in this sandbox rather than downloading a second Android framework below the app minSdk.
        val originalSdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 32)
            assertFalse(isRuntimeShaderSupported())
            var snapshot: MaterialSnapshot? = null
            SettingsComposeTestHost(
                appearance = AppearanceSettings(blurEnabled = true, themeMode = AppearanceThemeMode.LIGHT),
                content = {
                    EtaSettingsGlassPage {
                        val material = Modifier.etaSettingsGlass()
                        val current = MaterialSnapshot(LocalSettingsGlassActive.current, material, etaSettingsGlassPalette())
                        SideEffect { snapshot = current }
                        Box(Modifier.size(240.dp, 120.dp).then(material)) { Text("Shader fallback") }
                    }
                },
            ).use { host ->
                assertFallback(requireNotNull(snapshot))
                host.captureIfRequested("settings_shader_unavailable")
            }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
        assertEquals(originalSdk, Build.VERSION.SDK_INT)
    }

    @Test
    fun preferenceThemeRetainsAnExplicitMonetPrimary() {
        val chosenPrimary = Color(0xFF286454)
        var actual: Color? = null
        SettingsComposeTestHost(content = {
            val original = MiuixTheme.colorScheme
            CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(monetEnabled = true)) {
                MiuixTheme(colors = original.copy(primary = chosenPrimary)) {
                    EtaPreferenceTheme {
                        val primary = MiuixTheme.colorScheme.primary
                        SideEffect { actual = primary }
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }).use { assertEquals(chosenPrimary, actual) }
    }

    private fun assertFallback(snapshot: MaterialSnapshot) {
        assertTrue("Disabling blur must keep settings styling and its scope active", snapshot.active)
        assertFalse("Fallback must not instantiate a sampled blur modifier", snapshot.modifier.hasBlurElement())
        assertTrue("Fallback must provide its own readable surface", snapshot.modifier.elementNames().any { it.endsWith("BackgroundElement") })
        assertTrue("Fallback tint must remain close to opaque", snapshot.palette.fallbackSurface.alpha >= 0.94f)
    }

    private data class MaterialSnapshot(
        val active: Boolean,
        val modifier: Modifier,
        val palette: EtaSettingsGlassPalette,
    )

    private fun Modifier.elementNames(): List<String> = foldIn(emptyList()) { names, element -> names + element.javaClass.name }

    private fun Modifier.hasBlurElement(): Boolean = elementNames().any { it.startsWith("top.yukonga.miuix.kmp.blur.") }
}
