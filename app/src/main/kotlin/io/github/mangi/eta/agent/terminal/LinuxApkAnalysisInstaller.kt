package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class ApkAnalysisInstallStage {
    CHECKING,
    DOWNLOADING,
    PREPARING,
    INSTALLING_JAVA,
    ACTIVATING,
    VERIFYING,
    COMPLETE,
}

internal data class ApkAnalysisInstallProgress(
    val stage: ApkAnalysisInstallStage,
    val artifactName: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface ApkAnalysisInstallResult {
    data object AlreadyReady : ApkAnalysisInstallResult
    data object EnvironmentNotReady : ApkAnalysisInstallResult
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ApkAnalysisInstallResult
    data object Installed : ApkAnalysisInstallResult
    data class Failed(val stage: ApkAnalysisInstallStage) : ApkAnalysisInstallResult
}

internal fun linuxApkAnalysisReady(rootfs: File): Boolean =
    markerHasApkAnalysisRevision(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_REVISION)

internal fun linuxApkAnalysisUninstallable(rootfs: File): Boolean {
    if (markerHasApkAnalysisRevision(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_REVISION) ||
        markerHasApkAnalysisRevision(rootfs, LinuxApkAnalysisInstaller.LEGACY_REVISION)
    ) return true
    return LinuxApkAnalysisInstaller.COMMAND_PATHS.any { (name, _) ->
        val link = File(rootfs, "usr/local/bin/$name").toPath()
        Files.isSymbolicLink(link) &&
            isManagedApkAnalysisLink(name, runCatching { Files.readSymbolicLink(link).toString() }.getOrDefault(""))
    }
}

internal fun isManagedApkAnalysisLink(name: String, target: String): Boolean {
    val normalizedTarget = target.replace(File.separatorChar, '/')
    return LinuxApkAnalysisInstaller.COMMAND_PATHS[name]?.let { relative ->
        normalizedTarget == LinuxApkAnalysisInstaller.managedLinkTarget(relative) ||
            normalizedTarget == "/opt/eta/apk-analysis/current/$relative"
    } == true
}

private fun markerHasApkAnalysisRevision(rootfs: File, revision: Int): Boolean {
    val marker = File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER)
    return marker.isFile && runCatching {
        marker.useLines { lines -> lines.any { it.trim() == "profile=$revision" } }
    }.getOrDefault(false)
}

/** Keep the old profile recoverable until the new payload has passed its guest-side checks. */
internal suspend fun activateApkAnalysisProfile(
    activate: suspend () -> Boolean,
    verify: suspend () -> Boolean,
    publishMarker: suspend () -> Boolean,
    rollback: suspend () -> Unit,
): ApkAnalysisInstallStage? {
    var active = false
    var committed = false
    try {
        coroutineContext.ensureActive()
        // Finish the bounded directory swap before honoring cancellation; verification is cancellable.
        withContext(NonCancellable) { active = activate() }
        coroutineContext.ensureActive()
        if (!active) return ApkAnalysisInstallStage.ACTIVATING
        if (!verify()) return ApkAnalysisInstallStage.VERIFYING
        coroutineContext.ensureActive()
        withContext(NonCancellable) { committed = publishMarker() }
        return if (committed) null else ApkAnalysisInstallStage.VERIFYING
    } finally {
        if (active && !committed) withContext(NonCancellable) { rollback() }
    }
}

internal fun linuxApkJavaInstallCommand(distribution: LinuxDistribution): String =
    when (distribution) {
        LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk install openjdk25-jdk"
        LinuxDistribution.DEBIAN -> "/usr/local/bin/eta-apt install openjdk-25-jdk-headless"
    }

/** 为当前 Linux 发行版安装 Java 分析工具；APK 资源回编译仍需 ARM64 AAPT2 支持。 */
internal class LinuxApkAnalysisInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val artifactDownloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    fun isReady(): Boolean = linuxApkAnalysisReady(rootfs)

    suspend fun install(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit = {},
    ): ApkAnalysisInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    /**
     * 卸载 APK 分析工具：删掉 opt/eta/apk-analysis 与 usr/local/bin 下 Eta 建的包装，
     * 撤销安装记录。Debian 侧由包管理器装的 JVM 保留（/usr/bin/java 仍可用）。
     */
    suspend fun uninstall(): Boolean {
        installMutex.lock()
        return try {
            withContext(Dispatchers.IO) {
                if (!linuxApkAnalysisUninstallable(rootfs)) return@withContext false
                val result = InstallerShellRunner.run(
                    command = UNINSTALL_COMMAND,
                    timeoutSeconds = 120,
                    environment = distribution.terminalEnvironment,
                    linuxRootfsPath = rootfs.absolutePath,
                )
                AndroidAgentLogger.info(
                    "APK analysis profile action=uninstall " +
                        "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                        "exitCode=${result.exitCode} outputChars=${result.output.length}",
                )
                result.exitCode == 0 && !linuxApkAnalysisUninstallable(rootfs)
            }
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit,
    ): ApkAnalysisInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext ApkAnalysisInstallResult.AlreadyReady
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext ApkAnalysisInstallResult.EnvironmentNotReady
        }
        val availableBytes = rootfs.parentFile?.usableSpace ?: context.filesDir.usableSpace
        if (availableBytes < MIN_AVAILABLE_BYTES) {
            return@withContext ApkAnalysisInstallResult.InsufficientSpace(
                requiredBytes = MIN_AVAILABLE_BYTES,
                availableBytes = availableBytes,
            )
        }

        val downloadedArtifacts = linkedMapOf<VerifiedArtifact, File>()
        for (artifact in ARTIFACTS) {
            coroutineContext.ensureActive()
            val target = File(AlpineEnvironmentPaths.artifactDir(context), artifact.fileName)
            onProgress(
                ApkAnalysisInstallProgress(
                    stage = ApkAnalysisInstallStage.DOWNLOADING,
                    artifactName = artifact.id,
                    totalBytes = artifact.sizeBytes,
                ),
            )
            val downloaded = artifactDownloader.download(artifact, target) { downloadedBytes, totalBytes ->
                onProgress(
                    ApkAnalysisInstallProgress(
                        stage = ApkAnalysisInstallStage.DOWNLOADING,
                        artifactName = artifact.id,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!downloaded) {
                return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.DOWNLOADING)
            }
            downloadedArtifacts[artifact] = target
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.PREPARING))
        val staging = AlpineEnvironmentPaths.profileStagingDir(context, PROFILE_ID)
        if (!prepareStaging(staging, downloadedArtifacts)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.PREPARING)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.INSTALLING_JAVA))
        if (!installJava(rootfs)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.INSTALLING_JAVA)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.ACTIVATING))
        val failure = activateApkAnalysisProfile(
            activate = { activateStaging(rootfs, staging) },
            verify = {
                onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.VERIFYING))
                verifyInGuest(rootfs)
            },
            publishMarker = { publishMarker(rootfs) },
            rollback = { rollback(rootfs) },
        )
        if (failure != null) return@withContext ApkAnalysisInstallResult.Failed(failure)
        withContext(NonCancellable) { cleanupAfterSuccess(rootfs, downloadedArtifacts.values) }
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.COMPLETE))
        ApkAnalysisInstallResult.Installed
    }

    private fun prepareStaging(
        staging: File,
        artifacts: Map<VerifiedArtifact, File>,
    ): Boolean = try {
        staging.deleteRecursively()
        check(staging.mkdirs())
        val jadxArchive = artifacts.getValue(JADX_ARTIFACT)
        check(extractJadx(jadxArchive, staging))
        val libraryDir = File(staging, "lib").apply { check(mkdirs()) }
        artifacts.getValue(APKTOOL_ARTIFACT).copyTo(File(libraryDir, "apktool.jar"), overwrite = true)
        artifacts.getValue(SMALI_ARTIFACT).copyTo(File(libraryDir, "smali.jar"), overwrite = true)
        artifacts.getValue(BAKSMALI_ARTIFACT).copyTo(File(libraryDir, "baksmali.jar"), overwrite = true)
        context.assets.open(ETA_REVERSE_SOURCE_ASSET).use { input ->
            File(libraryDir, "EtaReverse.java").outputStream().use { output -> input.copyTo(output) }
        }
        val binDir = File(staging, "bin").apply { check(mkdirs()) }
        File(binDir, "java").writeText(JAVA_WRAPPER)
        File(binDir, "apktool").writeText(APKTOOL_WRAPPER)
        File(binDir, "smali").writeText(javaJarWrapper("smali.jar"))
        File(binDir, "baksmali").writeText(javaJarWrapper("baksmali.jar"))
        File(binDir, "eta-reverse").writeText(ETA_REVERSE_WRAPPER)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        AndroidAgentLogger.warn(
            "APK analysis profile action=prepare outcome=failed errorType=${throwable.safeLogType()}",
        )
        staging.deleteRecursively()
        false
    }

    private fun extractJadx(archive: File, staging: File): Boolean {
        val targets = mapOf(
            "bin/jadx" to File(staging, "jadx/bin/jadx"),
            "lib/jadx-$JADX_VERSION-all.jar" to File(staging, "jadx/lib/jadx-$JADX_VERSION-all.jar"),
            "LICENSE" to File(staging, "licenses/jadx-LICENSE"),
        )
        val extracted = mutableSetOf<String>()
        var extractedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targets[entry.name]
                if (target != null) {
                    check(!entry.isDirectory && extracted.add(entry.name))
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count.toLong()
                            check(extractedBytes <= MAX_JADX_EXTRACTED_BYTES)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return extracted == targets.keys
    }

    private suspend fun installJava(rootfs: File): Boolean {
        if (hasUsableJava(rootfs)) {
            AndroidAgentLogger.info(
                "APK analysis profile action=install_java outcome=skipped reason=java_present",
            )
            return true
        }
        val result = InstallerShellRunner.run(
            command = linuxApkJavaInstallCommand(distribution),
            timeoutSeconds = 900,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=install_java " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    /**
     * 环境里已有可用的 OpenJDK 25 时直接复用，不再走 apt：
     * 源里的小版本升级包动辄上百 MB，慢网下会把安装拖过超时并让整次安装失败。
     */
    private suspend fun hasUsableJava(rootfs: File): Boolean {
        val result = InstallerShellRunner.run(
            command = "/usr/bin/java -version 2>&1 | grep -q 'version \"25' && /usr/bin/javac -version",
            timeoutSeconds = 30,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        return result.exitCode == 0
    }

    private suspend fun activateStaging(rootfs: File, staging: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return activateRootless(rootfs, staging)
        }
        val profileRoot = File(rootfs, "opt/eta/apk-analysis")
        val current = File(profileRoot, "current")
        val installing = File(profileRoot, "current.installing")
        val previous = File(profileRoot, "previous")
        val executables = COMMAND_PATHS.values.joinToString(" ") { shellQuote(File(installing, it).absolutePath) }
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" mkdir -p ${shellQuote(profileRoot.absolutePath)} || exit 70
            "${'$'}eta_busybox" rm -rf ${shellQuote(installing.absolutePath)} ${shellQuote(previous.absolutePath)} || exit 70
            "${'$'}eta_busybox" mv ${shellQuote(staging.absolutePath)} ${shellQuote(installing.absolutePath)} || exit 71
            "${'$'}eta_busybox" chmod 0755 $executables || exit 72
            ${relinkCommands(rootfs, current)}
            eta_restore_previous() {
              "${'$'}eta_busybox" rm -rf ${shellQuote(current.absolutePath)} || return 1
              if [ -d ${shellQuote(previous.absolutePath)} ]; then
                "${'$'}eta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)} || return 1
              fi
              eta_link_commands
            }
            if [ -d ${shellQuote(current.absolutePath)} ]; then
              "${'$'}eta_busybox" mv ${shellQuote(current.absolutePath)} ${shellQuote(previous.absolutePath)} || exit 73
            fi
            "${'$'}eta_busybox" mv ${shellQuote(installing.absolutePath)} ${shellQuote(current.absolutePath)} || {
              eta_restore_previous || exit 75
              exit 74
            }
            eta_link_commands || {
              eta_restore_previous || exit 75
              exit 76
            }
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 60,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=activate " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} exitCode=${result.exitCode}",
        )
        return result.exitCode == 0
    }

    private fun relinkCommands(rootfs: File, current: File): String = buildString {
        val localBin = File(rootfs, "usr/local/bin")
        val busybox = "\"\$eta_busybox\""
        appendLine("eta_link_commands() {")
        appendLine("  $busybox mkdir -p ${shellQuote(localBin.absolutePath)} || return 1")
        COMMAND_PATHS.forEach { (name, relative) ->
            val link = shellQuote(File(localBin, name).absolutePath)
            val installed = shellQuote(File(current, relative).absolutePath)
            val target = shellQuote(linkTarget(relative))
            val absoluteTarget = shellQuote(absoluteLinkTarget(relative))
            appendLine("  if [ -L $link ] && { [ \"\$($busybox readlink $link)\" = $target ] || [ \"\$($busybox readlink $link)\" = $absoluteTarget ]; }; then")
            appendLine("    [ -f $installed ] || $busybox rm -f $link || return 1")
            appendLine("  elif [ -f $installed ]; then")
            appendLine("    [ ! -e $link ] && [ ! -L $link ] || return 1")
            appendLine("    $busybox ln -s $target $link || return 1")
            appendLine("  fi")
        }
        appendLine("  return 0")
        append("}")
    }

    private suspend fun verifyInGuest(rootfs: File): Boolean {
        val result = InstallerShellRunner.run(
            command = VERIFY_COMMAND,
            timeoutSeconds = 90,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=verify " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun publishMarker(rootfs: File): Boolean = InstallerShellRunner.run(
        command = PUBLISH_MARKER_COMMAND,
        timeoutSeconds = 15,
        environment = distribution.terminalEnvironment,
        linuxRootfsPath = rootfs.absolutePath,
    ).exitCode == 0

    private suspend fun rollback(rootfs: File) {
        val profileRoot = File(rootfs, "opt/eta/apk-analysis")
        val current = File(profileRoot, "current")
        val previous = File(profileRoot, "previous")
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            runCatching { rollbackRootless(rootfs) }.onFailure {
                AndroidAgentLogger.warn("APK analysis profile action=rollback errorType=${it.safeLogType()}")
            }
            return
        }
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" rm -rf ${shellQuote(current.absolutePath)} || exit 71
            "${'$'}eta_busybox" rm -f ${shellQuote(File(rootfs, PENDING_MARKER).absolutePath)} || exit 72
            if [ -d ${shellQuote(previous.absolutePath)} ]; then
              "${'$'}eta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)} || exit 73
            fi
            ${relinkCommands(rootfs, current)}
            eta_link_commands
        """.trimIndent()
        val result = InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
        if (result.exitCode != 0) AndroidAgentLogger.warn("APK analysis profile action=rollback exitCode=${result.exitCode}")
    }

    private suspend fun cleanupAfterSuccess(rootfs: File, artifacts: Collection<File>) {
        artifacts.forEach(File::delete)
        val previous = File(rootfs, "opt/eta/apk-analysis/previous")
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            previous.deleteRecursively()
            return
        }
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" rm -rf ${shellQuote(previous.absolutePath)}
        """.trimIndent()
        InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
    }

    companion object {
        private const val PROFILE_ID = "apk-analysis"
        private const val PROFILE_DIR = "/opt/eta/apk-analysis"
        private const val CURRENT_DIR = "$PROFILE_DIR/current"
        private const val PENDING_MARKER = AlpineEnvironmentPaths.APK_ANALYSIS_MARKER + ".new"
        internal const val ETA_REVERSE_SOURCE_ASSET = "reverse/EtaReverse.java"
        internal const val LEGACY_REVISION = 1
        internal val COMMAND_PATHS = mapOf(
            "java" to "bin/java",
            "jadx" to "jadx/bin/jadx",
            "apktool" to "bin/apktool",
            "smali" to "bin/smali",
            "baksmali" to "bin/baksmali",
            "eta-reverse" to "bin/eta-reverse",
        )

        private fun linkTarget(relative: String): String = "../../..$CURRENT_DIR/$relative"
        private fun absoluteLinkTarget(relative: String): String = "$CURRENT_DIR/$relative"
        internal fun managedLinkTarget(relative: String): String = linkTarget(relative)
        private fun isManagedLinkTarget(target: Path, relative: String): Boolean =
            target == Paths.get(linkTarget(relative)) || target == Paths.get(absoluteLinkTarget(relative))

        internal fun activateRootless(rootfs: File, staging: File): Boolean {
            val profileRoot = File(rootfs, PROFILE_DIR.removePrefix("/")).apply { mkdirs() }
            val current = File(profileRoot, "current")
            val previous = File(profileRoot, "previous")
            if (previous.exists() && !previous.deleteRecursively()) return false
            if (current.exists() && !current.renameTo(previous)) return false
            try {
                if (!staging.renameTo(current)) throw IOException("APK tools activation failed")
                for (relative in COMMAND_PATHS.values) {
                    if (!File(current, relative).setExecutable(true, false)) throw IOException("APK tool chmod failed")
                }
                relinkRootless(rootfs, current)
                return true
            } catch (error: IOException) {
                runCatching { rollbackRootless(rootfs) }.onFailure {
                    AndroidAgentLogger.warn("APK analysis profile action=rollback errorType=${it.safeLogType()}")
                }
                AndroidAgentLogger.warn("APK analysis profile action=activate errorType=${error.safeLogType()}")
                return false
            }
        }

        internal fun rollbackRootless(rootfs: File) {
            val profileRoot = File(rootfs, PROFILE_DIR.removePrefix("/"))
            val current = File(profileRoot, "current")
            val previous = File(profileRoot, "previous")
            if (!current.deleteRecursively()) throw IOException("APK tools rollback cleanup failed")
            if (previous.exists() && !previous.renameTo(current)) throw IOException("APK tools restore failed")
            relinkRootless(rootfs, current)
            Files.deleteIfExists(File(rootfs, PENDING_MARKER).toPath())
        }

        private fun relinkRootless(rootfs: File, current: File) {
            val localBin = File(rootfs, "usr/local/bin")
            if (!localBin.isDirectory && !localBin.mkdirs()) throw IOException("APK tools link directory unavailable")
            for ((name, relative) in COMMAND_PATHS) {
                val link = File(localBin, name).toPath()
                val target = Paths.get(linkTarget(relative))
                val managed = Files.isSymbolicLink(link) &&
                    isManagedLinkTarget(Files.readSymbolicLink(link), relative)
                if (!File(current, relative).isFile) {
                    if (managed) Files.deleteIfExists(link)
                } else if (!managed) {
                    if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) throw IOException("APK tool link already exists")
                    Files.createSymbolicLink(link, target)
                }
            }
        }

        private const val JADX_VERSION = "1.5.6"
        private const val APKTOOL_VERSION = "3.0.3"
        private const val SMALI_VERSION = "3.0.10"
        private const val MAX_JADX_EXTRACTED_BYTES = 128L * 1024L * 1024L
        internal const val MIN_AVAILABLE_BYTES = 768L * 1024L * 1024L

        private val installMutex = Mutex()
        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )

        internal val JADX_ARTIFACT = githubReleaseArtifact(
            id = "jadx",
            version = JADX_VERSION,
            fileName = "jadx-$JADX_VERSION.zip",
            repository = "skylot/jadx",
            tag = "v$JADX_VERSION",
            sha256 = "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974",
            sizeBytes = 72_646_741L,
        )
        internal val APKTOOL_ARTIFACT = githubReleaseArtifact(
            id = "apktool",
            version = APKTOOL_VERSION,
            fileName = "apktool_$APKTOOL_VERSION.jar",
            repository = "iBotPeaches/Apktool",
            tag = "v$APKTOOL_VERSION",
            sha256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423",
            sizeBytes = 15_478_013L,
        )
        internal val SMALI_ARTIFACT = githubReleaseArtifact(
            id = "smali",
            version = SMALI_VERSION,
            fileName = "smali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "32fa0e88a6c397b3922201adf5f3e534fbaed5a663c71d0c558c3ddce0af844a",
            sizeBytes = 5_384_623L,
        )
        internal val BAKSMALI_ARTIFACT = githubReleaseArtifact(
            id = "baksmali",
            version = SMALI_VERSION,
            fileName = "baksmali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "37ae4a41a8886e15c20b8362fa4250f96bbdb55e1a608199ad8b5dff068b588f",
            sizeBytes = 4_447_943L,
        )
        internal val ARTIFACTS = listOf(
            JADX_ARTIFACT,
            APKTOOL_ARTIFACT,
            SMALI_ARTIFACT,
            BAKSMALI_ARTIFACT,
        )

        private fun githubReleaseArtifact(
            id: String,
            version: String,
            fileName: String,
            repository: String,
            tag: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val officialUrl = "https://github.com/$repository/releases/download/$tag/$fileName"
            return VerifiedArtifact(
                id = id,
                version = version,
                fileName = fileName,
                url = officialUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
            )
        }

        internal val APKTOOL_WRAPPER = """
            #!/bin/sh
            case "${'$'}{1:-}" in
              b|build)
                echo "APKTOOL_BUILD_UNAVAILABLE: Eta APK 分析档案暂不包含 ARM64 AAPT2，仅支持解码与检查。" >&2
                exit 64
                ;;
            esac
            exec java -jar /opt/eta/apk-analysis/current/lib/apktool.jar "${'$'}@"
        """.trimIndent() + "\n"

        internal val JAVA_WRAPPER = """
            #!/bin/sh
            exec /usr/bin/java "${'$'}@"
        """.trimIndent() + "\n"

        internal val VERIFY_COMMAND = """
            /usr/local/bin/java -version >/dev/null 2>&1 || exit 81
            /usr/local/bin/jadx --version >/dev/null 2>&1 || exit 82
            /usr/local/bin/apktool --version >/dev/null 2>&1 || exit 83
            /usr/local/bin/smali --version >/dev/null 2>&1 || exit 84
            /usr/local/bin/baksmali --version >/dev/null 2>&1 || exit 85
            /usr/local/bin/eta-reverse probe >/dev/null 2>&1 || exit 87
        """.trimIndent()

        internal val PUBLISH_MARKER_COMMAND = """
            set -e
            rm -f /$PENDING_MARKER
            cat > /$PENDING_MARKER <<'ETA_APK_ANALYSIS_EOF'
            profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}
            jadx=$JADX_VERSION
            apktool=$APKTOOL_VERSION
            smali=$SMALI_VERSION
            ETA_APK_ANALYSIS_EOF
            chmod 0644 /$PENDING_MARKER
            mv -f /$PENDING_MARKER /${AlpineEnvironmentPaths.APK_ANALYSIS_MARKER}
        """.trimIndent()

        internal val UNINSTALL_COMMAND = buildString {
            appendLine("set -e")
            COMMAND_PATHS.forEach { (name, relative) ->
                val link = "/usr/local/bin/$name"
                appendLine("eta_link=${shellQuote(link)}")
                appendLine("if [ -L \"\$eta_link\" ]; then")
                appendLine("  eta_target=\$(readlink \"\$eta_link\") || exit 76")
                appendLine(
                    "  if [ \"\$eta_target\" = ${shellQuote(linkTarget(relative))} ] || " +
                        "[ \"\$eta_target\" = ${shellQuote(absoluteLinkTarget(relative))} ]; then rm -f \"\$eta_link\"; fi",
                )
                appendLine("fi")
            }
            appendLine("rm -rf $PROFILE_DIR")
            appendLine("rm -f /${AlpineEnvironmentPaths.APK_ANALYSIS_MARKER} /$PENDING_MARKER")
        }

        internal val ETA_REVERSE_WRAPPER = """
            #!/bin/sh
            exec $CURRENT_DIR/bin/java -Deta.reverse.jadx=$CURRENT_DIR/jadx/lib/jadx-$JADX_VERSION-all.jar $CURRENT_DIR/lib/EtaReverse.java "${'$'}@"
        """.trimIndent() + "\n"

        private fun javaJarWrapper(fileName: String): String =
            "#!/bin/sh\nexec java -jar /opt/eta/apk-analysis/current/lib/$fileName \"${'$'}@\"\n"
    }
}
