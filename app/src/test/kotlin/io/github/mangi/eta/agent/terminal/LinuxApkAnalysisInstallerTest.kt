package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxApkAnalysisInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactManifestPinsOfficialHttpsDownloadsAndIntegrityMetadata() {
        val artifacts = LinuxApkAnalysisInstaller.ARTIFACTS

        assertEquals(listOf("jadx", "apktool", "smali", "baksmali"), artifacts.map { it.id })
        assertTrue(artifacts.all { artifact -> artifact.url.startsWith("https://github.com/") })
        assertTrue(artifacts.all { artifact -> artifact.preferredUrls.size == 1 })
        assertTrue(
            artifacts.all { artifact ->
                artifact.preferredUrls.single().startsWith("https://gh-proxy.com/")
            },
        )
        assertTrue(
            artifacts.all { artifact ->
                artifact.preferredUrls.all { preferred -> preferred.endsWith(artifact.url) }
            },
        )
        assertTrue(artifacts.all { artifact -> artifact.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(artifacts.all { artifact -> artifact.sizeBytes > 1_000_000L })
        assertEquals(97_957_320L, artifacts.sumOf { artifact -> artifact.sizeBytes })
        assertTrue(LinuxApkAnalysisInstaller.MIN_AVAILABLE_BYTES > artifacts.sumOf { it.sizeBytes } * 2)
    }

    @Test
    fun cachedArtifactVerificationRejectsWrongSizeAndDigest() {
        val file = temporaryFolder.newFile("artifact.bin")
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = file.name,
            url = "https://example.invalid/artifact.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
        )
        val downloader = VerifiedArtifactDownloader()

        file.writeText("ab")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abd")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abc")
        assertTrue(downloader.verify(artifact, file))
    }

    @Test
    fun apktoolWrapperRejectsBuildWithoutArm64Aapt2() {
        val wrapper = LinuxApkAnalysisInstaller.APKTOOL_WRAPPER

        assertTrue(wrapper.contains("b|build"))
        assertTrue(wrapper.contains("APKTOOL_BUILD_UNAVAILABLE"))
        assertTrue(wrapper.contains("exit 64"))
        assertTrue(wrapper.contains("exec java -jar"))
    }

    @Test
    fun javaWrapperUsesDistributionJavaAlternative() {
        val wrapper = LinuxApkAnalysisInstaller.JAVA_WRAPPER

        assertTrue(wrapper.contains("exec /usr/bin/java"))
    }

    @Test
    fun javaRuntimeUsesSelectedDistributionStablePackage() {
        assertEquals(
            "/usr/local/bin/eta-apk install openjdk25-jdk",
            linuxApkJavaInstallCommand(LinuxDistribution.ALPINE),
        )
        assertEquals(
            "/usr/local/bin/eta-apt install openjdk-25-jdk-headless",
            linuxApkJavaInstallCommand(LinuxDistribution.DEBIAN),
        )
    }

    @Test
    fun etaReverseUsesPinnedGuestToolsAndIsAHeavyCommand() {
        val current = "/opt/eta/apk-analysis/current"
        val jadxVersion = LinuxApkAnalysisInstaller.JADX_ARTIFACT.version
        assertEquals(
            "#!/bin/sh\nexec $current/bin/java -Deta.reverse.jadx=$current/jadx/lib/jadx-$jadxVersion-all.jar " +
                "$current/lib/EtaReverse.java \"\$@\"\n",
            LinuxApkAnalysisInstaller.ETA_REVERSE_WRAPPER,
        )
        assertEquals(
            setOf("java", "jadx", "apktool", "smali", "baksmali", "eta-reverse"),
            LinuxApkAnalysisInstaller.COMMAND_PATHS.keys,
        )
        assertTrue(LinuxApkAnalysisInstaller.VERIFY_COMMAND.contains("/usr/local/bin/eta-reverse probe >/dev/null 2>&1 || exit 87"))
        assertFalse(LinuxApkAnalysisInstaller.VERIFY_COMMAND.contains(AlpineEnvironmentPaths.APK_ANALYSIS_MARKER))
        assertTrue(LinuxApkAnalysisInstaller.UNINSTALL_COMMAND.contains("/opt/eta/apk-analysis/current/bin/eta-reverse"))
        assertTrue(HeavyCommandGate.isHeavy("eta-reverse decompile --workspace /workspace --input sample.apk"))
        assertTrue(HeavyCommandGate.isHeavy("/usr/local/bin/eta-reverse probe"))
        assertFalse(HeavyCommandGate.isHeavy("ls /workspace"))
    }

    @Test
    fun apkProfilePublishesMarkerOnlyAfterSuccessfulProbe() = runBlocking {
        for ((probeOk, publishOk) in listOf(false to true, true to false, true to true)) {
            val calls = mutableListOf<String>()
            val result = activateApkAnalysisProfile(
                activate = { calls += "activate"; true },
                verify = { calls += "probe"; probeOk },
                publishMarker = { calls += "publish"; publishOk },
                rollback = { calls += "rollback" },
            )
            when {
                !probeOk -> assertEquals(listOf("activate", "probe", "rollback"), calls)
                !publishOk -> assertEquals(listOf("activate", "probe", "publish", "rollback"), calls)
                else -> assertEquals(listOf("activate", "probe", "publish"), calls)
            }
            if (probeOk && publishOk) assertNull(result)
            else assertEquals(ApkAnalysisInstallStage.VERIFYING, result)
        }
    }

    @Test
    fun cancellingApkProfileVerificationRollsBackWithoutPublishingMarker() = runBlocking {
        val verifying = CompletableDeferred<Unit>()
        var rolledBack = false
        val installation = launch {
            activateApkAnalysisProfile(
                activate = { true },
                verify = {
                    verifying.complete(Unit)
                    awaitCancellation()
                },
                publishMarker = { error("Cancelled probe must not publish the marker") },
                rollback = {
                    currentCoroutineContext().ensureActive()
                    rolledBack = true
                },
            )
        }
        withTimeout(5_000) { verifying.await() }
        installation.cancelAndJoin()
        assertTrue(rolledBack)
    }

    @Test
    fun legacyApkProfileAndExactAbsoluteLinksRemainUninstallable() {
        val rootfs = temporaryFolder.newFolder("legacy-apk-rootfs")
        val marker = File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER)
        marker.writeText("profile=${LinuxApkAnalysisInstaller.LEGACY_REVISION}\n")
        assertFalse(linuxApkAnalysisReady(rootfs))
        assertTrue(linuxApkAnalysisUninstallable(rootfs))
        assertTrue(LinuxApkAnalysisInstaller.UNINSTALL_COMMAND.contains("/usr/local/bin/eta-reverse"))

        marker.writeText("profile=999\n")
        assertFalse(linuxApkAnalysisUninstallable(rootfs))
        val localBin = File(rootfs, "usr/local/bin").also { check(it.mkdirs()) }
        val jadxLink = File(localBin, "jadx")
        try {
            Files.createSymbolicLink(
                jadxLink.toPath(),
                Paths.get("/opt/eta/apk-analysis/current/jadx/bin/jadx"),
            )
        } catch (error: IOException) {
            assumeNoException(error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }
        assertTrue(linuxApkAnalysisUninstallable(rootfs))
        assertTrue(isManagedApkAnalysisLink("jadx", "/opt/eta/apk-analysis/current/jadx/bin/jadx"))
        val hostAbsoluteTarget = "/opt/eta/apk-analysis/current/jadx/bin/jadx"
            .replace('/', File.separatorChar)
        assertTrue(isManagedApkAnalysisLink("jadx", hostAbsoluteTarget))
        assertFalse(isManagedApkAnalysisLink("jadx", "/opt/custom/jadx"))
        assertFalse(isManagedApkAnalysisLink("jadx", "\\opt\\custom\\jadx"))
        assertFalse(isManagedApkAnalysisLink("custom", "/opt/eta/apk-analysis/current/bin/java"))

        Files.delete(jadxLink.toPath())
        Files.createSymbolicLink(jadxLink.toPath(), Paths.get("/opt/custom/jadx"))
        assertFalse(linuxApkAnalysisUninstallable(rootfs))
    }

    @Test
    fun rootlessProfileRollsBackToLegacyCommandsWithoutLeavingEtaReverseLink() {
        val rootfs = temporaryFolder.newFolder("apk-rootfs")
        val staged = temporaryFolder.newFolder("apk-staging")
        val current = File(rootfs, "opt/eta/apk-analysis/current")
        val localBin = File(rootfs, "usr/local/bin").also { check(it.mkdirs()) }
        val javaLink = File(localBin, "java")
        val reverseLink = File(localBin, "eta-reverse")
        for ((name, relative) in LinuxApkAnalysisInstaller.COMMAND_PATHS) {
            if (name != "eta-reverse") writeFixture(File(current, relative), "old:$name")
            writeFixture(File(staged, relative), "new:$name")
        }
        try {
            Files.createSymbolicLink(javaLink.toPath(), Paths.get("../../../opt/eta/apk-analysis/current/bin/java"))
        } catch (error: IOException) {
            assumeNoException(error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }
        val marker = File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).apply { writeText("profile=1\n") }
        val pendingMarker = File(rootfs, marker.name + ".new").apply { writeText("profile=2\n") }
        writeFixture(File(localBin, "user-tool"), "unrelated")

        assertTrue(LinuxApkAnalysisInstaller.activateRootless(rootfs, staged))
        assertTrue(Files.isSymbolicLink(reverseLink.toPath()))
        assertEquals("new:eta-reverse", reverseLink.readText())
        assertEquals("new:java", javaLink.readText())
        assertFalse(linuxApkAnalysisReady(rootfs))

        LinuxApkAnalysisInstaller.rollbackRootless(rootfs)
        assertFalse(Files.isSymbolicLink(reverseLink.toPath()))
        assertEquals("old:java", javaLink.readText())
        assertEquals("old:jadx", File(localBin, "jadx").readText())
        assertEquals("unrelated", File(localBin, "user-tool").readText())
        assertEquals("profile=1\n", marker.readText())
        assertFalse(pendingMarker.exists())
        assertFalse(linuxApkAnalysisReady(rootfs))
    }

    private fun writeFixture(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun downloaderUsesPinnedFallbackAfterOfficialEndpointFails() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedHosts += request.url.host
                if (request.url.host == "official.invalid") {
                    response(request, 502, ByteArray(0))
                } else {
                    response(request, 200, "abc".encodeToByteArray())
                }
            }
            .build()
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = "fixture.bin",
            url = "https://official.invalid/fixture.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
            fallbackUrls = listOf("https://fallback.invalid/fixture.bin"),
        )
        val target = File(temporaryFolder.root, artifact.fileName)

        assertTrue(VerifiedArtifactDownloader(client, NoopLogger).download(artifact, target))
        assertEquals(listOf("official.invalid", "fallback.invalid"), requestedHosts)
        assertEquals("abc", target.readText())
    }

    @Test
    fun downloaderUsesPreferredMirrorBeforeOfficialEndpoint() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedHosts += request.url.host
                response(request, 200, "abc".encodeToByteArray())
            }
            .build()
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = "preferred-fixture.bin",
            url = "https://official.invalid/fixture.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
            preferredUrls = listOf("https://mirror.invalid/fixture.bin"),
        )
        val target = File(temporaryFolder.root, artifact.fileName)

        assertTrue(VerifiedArtifactDownloader(client, NoopLogger).download(artifact, target))
        assertEquals(listOf("mirror.invalid"), requestedHosts)
    }

    private fun response(request: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Failed")
            .body(body.toResponseBody())
            .build()

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
