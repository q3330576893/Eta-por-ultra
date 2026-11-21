/* Part of Eta Ultra. See the repository LICENSE (PolyForm Noncommercial 1.0.0).
 * This helper performs static APK inspection/decompilation; it never launches an APK.
 * Requires JDK 17+; the optional Eta APK-analysis profile supplies JDK 25 and JADX.
 */
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.*;

public final class EtaReverse {
    static final String VERSION = "0.1.0";
    static final String PROTOCOL = "eta.reverse.v1";
    static final long MAX_INPUT = 256L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 100000;
    static final int MAX_FILES = 50000;
    static final long MAX_LOG = 2L * 1024 * 1024;
    static final int REPORT_ARTIFACTS = 128;
    static volatile Process ownedChild;

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(ownedChild), "eta-reverse-stop"));
        Map<String,Object> result;
        int exit = 0;
        try {
            String command = args.length == 0 ? "help" : args[0];
            Map<String,String> options = options(args);
            if (command.equals("help") || command.equals("--help") || command.equals("--version")) {
                result = base("available", true);
                result.put("commands", List.of("probe", "inspect --workspace PATH --input PATH", "decompile --workspace PATH --input PATH [--class FQCN] [--timeout-seconds 15..150] [--max-output-mb 1..256]"));
            } else if (command.equals("probe")) {
                if (!options.isEmpty()) throw new Problem("BAD_ARGUMENTS", "probe takes no sample or workspace");
                result = probe(jadxJar(), null);
                if (!Boolean.TRUE.equals(result.get("ok"))) exit = 3;
            } else if (command.equals("inspect") || command.equals("decompile")) {
                result = analyze(command, options);
                if (!Boolean.TRUE.equals(result.get("ok"))) exit = 1;
            } else throw new Problem("BAD_COMMAND", "Unknown operation");
        } catch (Problem problem) {
            result = base("failed", false); result.put("code", problem.code); result.put("message", problem.getMessage()); exit = 2;
        } catch (Throwable problem) {
            result = base("failed", false); result.put("code", "ANALYSIS_ERROR"); result.put("error_type", problem.getClass().getSimpleName()); exit = 1;
        }
        System.out.println(json(result));
        if (exit != 0) System.exit(exit);
    }

    static Map<String,String> options(String[] args) throws Problem {
        Set<String> allowed = Set.of("--workspace", "--input", "--class", "--timeout-seconds", "--max-output-mb");
        Map<String,String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (!allowed.contains(args[index]) || index + 1 >= args.length || result.containsKey(args[index]))
                throw new Problem("BAD_ARGUMENTS", "Unknown, repeated or incomplete option");
            result.put(args[index], args[index + 1]);
        }
        return result;
    }

    static Map<String,Object> analyze(String command, Map<String,String> options) throws Exception {
        String rootText = required(options, "--workspace"), inputText = required(options, "--input");
        Path requestedRoot = Path.of(rootText).toAbsolutePath().normalize();
        Path root = requestedRoot.toRealPath();
        if (!Files.isDirectory(root) || !Files.isWritable(root)) throw new Problem("BAD_WORKSPACE", "Choose a writable workspace directory");
        Path candidate = Path.of(inputText);
        if (!candidate.isAbsolute()) candidate = root.resolve(candidate);
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(root) && !candidate.startsWith(requestedRoot)) throw new Problem("OUTSIDE_WORKSPACE", "Import the selected APK into this workspace first");
        Path input = candidate.toRealPath();
        if (!input.startsWith(root) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
            throw new Problem("OUTSIDE_WORKSPACE", "Selected sample must be a regular file inside this workspace");
        long inputSize = Files.size(input);
        if (inputSize <= 0 || inputSize > MAX_INPUT) throw new Problem("INPUT_LIMIT", "APK must be between 1 byte and 256 MiB");
        if (command.equals("inspect") && (options.containsKey("--class") || options.containsKey("--timeout-seconds") || options.containsKey("--max-output-mb")))
            throw new Problem("BAD_ARGUMENTS", "Decompiler options apply only to decompile");
        int timeout = integer(options, "--timeout-seconds", 120, 15, 150);
        long outputLimit = integer(options, "--max-output-mb", 64, 1, 256) * 1024L * 1024L;
        String className = options.get("--class");
        if (className != null && !validClass(className)) throw new Problem("BAD_CLASS", "Expected an exact qualified Java class name");
        Path parent = root.resolve(".eta-reverse");
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(parent) || !Files.isDirectory(parent)))
            throw new Problem("BAD_OUTPUT", "Reserved report directory is not an ordinary directory");
        Files.createDirectories(parent);
        if (!parent.toRealPath().startsWith(root)) throw new Problem("BAD_OUTPUT", "Report directory is outside workspace");
        Path run = Files.createTempDirectory(parent, "run-");
        Path snapshot = run.resolve("input.apk");
        Path report = run.resolve("report.json");
        Map<String,Object> data = base("running", false);
        data.put("task_id", run.getFileName().toString()); data.put("command", command);
        data.put("started_at", Instant.now().toString()); data.put("workspace", root.toString());
        data.put("input_path", root.relativize(input).toString());
        data.put("target_execution", false); data.put("signature_verification", "not_performed");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream source = Files.newInputStream(input); OutputStream target = Files.newOutputStream(snapshot, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = source.read(buffer)) >= 0) {
                    copied += count;
                    if (copied > MAX_INPUT) throw new Problem("INPUT_LIMIT", "Sample grew beyond the input limit");
                    target.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            if (copied == 0) throw new Problem("EMPTY_INPUT", "Selected sample is empty");
            String inputHash = hex(digest.digest());
            data.put("input_sha256", inputHash); data.put("input_bytes", copied);
            data.put("snapshot_retained", false);
            data.put("inspection", inspect(snapshot));
            writeReport(report, data);
            if (command.equals("inspect")) {
                data.put("status", "inspected"); data.put("ok", true);
            } else {
                Path jar = jadxJar();
                Map<String,Object> engine = probe(jar, run);
                data.put("engine", engine);
                if (!Boolean.TRUE.equals(engine.get("ok"))) throw new Problem("ENGINE_UNAVAILABLE", "Install or repair the optional APK analysis profile, then run probe");
                String engineHash = (String) engine.get("jar_sha256");
                Path destination = run.resolve("decompiled");
                Files.createDirectory(destination);
                Path log = run.resolve("jadx.log");
                List<String> argv = new ArrayList<>(List.of(javaExecutable(), "-Xmx" + heapMiB() + "m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", jar.toString(), "jadx.cli.JadxCLI", "--no-res", "-j", "1", "-d", destination.toString()));
                if (className != null) { argv.add("--single-class"); argv.add(className); }
                argv.add(snapshot.toString());
                RunResult execution = execute(argv, root, log, destination, timeout, outputLimit);
                data.put("exit_code", execution.exitCode); data.put("timed_out", execution.timedOut);
                data.put("resource_limited", execution.limited); data.put("timeout_seconds", timeout);
                data.put("max_output_bytes", outputLimit); data.put("log_path", root.relativize(log).toString());
                data.put("log_preview", tail(log, 2048));
                if (execution.limited) {
                    data.put("limited_output_removed", removeOwnedOutput(run));
                    trimLog(log, MAX_LOG);
                }
                List<Map<String,Object>> artifacts = Files.exists(destination) ? artifacts(destination, root, outputLimit) : new ArrayList<>();
                data.put("artifact_count", artifacts.size());
                data.put("artifacts_truncated", artifacts.size() > REPORT_ARTIFACTS);
                data.put("artifacts", artifacts.subList(0, Math.min(REPORT_ARTIFACTS, artifacts.size())));
                if (!inputHash.equals(sha256(snapshot))) throw new Problem("INPUT_CHANGED", "Owned input snapshot changed during analysis");
                if (!engineHash.equals(sha256(jar))) throw new Problem("ENGINE_CHANGED", "Analysis engine changed during execution");
                boolean complete = !execution.timedOut && !execution.limited && execution.exitCode == 0 && !artifacts.isEmpty();
                data.put("ok", complete);
                data.put("status", complete ? "completed" : execution.timedOut ? "timed_out" : execution.limited ? "resource_limit" : !artifacts.isEmpty() ? "partial" : "failed");
                data.put("code", complete ? "OK" : execution.timedOut ? "TIMEOUT" : execution.limited ? "OUTPUT_LIMIT" : execution.exitCode == 0 ? "EMPTY_RESULT" : "ENGINE_EXIT_NONZERO");
                data.put("representation", "decompiled_java");
            }
        } catch (Problem problem) {
            data.put("ok", false); data.put("status", "failed"); data.put("code", problem.code); data.put("message", problem.getMessage());
            if (problem.code.startsWith("OUTPUT_")) data.put("limited_output_removed", removeOwnedOutput(run));
        } catch (Throwable problem) {
            data.put("ok", false); data.put("status", "failed"); data.put("code", "ANALYSIS_ERROR"); data.put("error_type", problem.getClass().getSimpleName());
        } finally {
            stop(ownedChild); ownedChild = null;
            try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { data.put("snapshot_retained", true); }
        }
        data.put("finished_at", Instant.now().toString());
        writeReport(report, data);
        Map<String,Object> summary = base((String) data.get("status"), Boolean.TRUE.equals(data.get("ok")));
        for (String key : List.of("task_id", "code", "input_sha256", "input_bytes", "artifact_count", "artifacts_truncated", "exit_code", "timed_out", "resource_limited", "representation", "message", "error_type"))
            if (data.containsKey(key)) summary.put(key, data.get(key));
        summary.put("report_path", report.toString()); summary.put("report_sha256", sha256(report));
        summary.put("target_execution", false); summary.put("signature_verification", "not_performed");
        return summary;
    }

    static Map<String,Object> inspect(Path sample) throws Exception {
        List<Map<String,Object>> dex = new ArrayList<>(); Set<String> abis = new TreeSet<>();
        long classDefinitions = 0; boolean allHeadersKnown = true; int entryCount = 0;
        try (ZipFile zip = new ZipFile(sample.toFile())) {
            if (zip.getEntry("AndroidManifest.xml") == null) throw new Problem("NOT_AN_APK", "Archive has no AndroidManifest.xml");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ZIP_ENTRIES) throw new Problem("ZIP_ENTRY_LIMIT", "APK has too many archive entries");
                String name = entry.getName();
                if (name.matches("lib/[^/]+/[^/]+\\.so")) abis.add(name.split("/")[1]);
                if (!name.matches("classes(?:[0-9]+)?\\.dex")) continue;
                Map<String,Object> item = obj("name", name, "bytes", entry.getSize(), "payload_integrity_checked", false);
                byte[] header;
                try (InputStream source = zip.getInputStream(entry)) { header = source.readNBytes(112); }
                String version = header.length >= 8 ? new String(header, 4, 3, StandardCharsets.US_ASCII) : "";
                item.put("version", version);
                boolean known = header.length == 112 && header[0]=='d' && header[1]=='e' && header[2]=='x' && header[3]=='\n' && header[7]==0 && Set.of("035","037","038","039","040").contains(version);
                if (known) {
                    ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    long declaredSize = Integer.toUnsignedLong(buffer.getInt(32));
                    known = buffer.getInt(40) == 0x12345678 && Integer.toUnsignedLong(buffer.getInt(36)) >= 112 && declaredSize >= 112 && declaredSize <= entry.getSize();
                    if (known) { long count = Integer.toUnsignedLong(buffer.getInt(96)); item.put("declared_class_definitions", count); classDefinitions += count; }
                }
                item.put("header_supported", known); allHeadersKnown &= known; dex.add(item);
            }
        }
        return obj("archive_entries", entryCount, "dex_files", dex, "dex_headers_complete", allHeadersKnown,
            "declared_class_definitions", allHeadersKnown ? classDefinitions : null, "native_abis", new ArrayList<>(abis),
            "manifest_decoded", false, "signature_verified", false);
    }

    static Map<String,Object> probe(Path jar, Path run) throws Exception {
        Map<String,Object> result = base("unavailable", false);
        result.put("java_version", System.getProperty("java.version"));
        result.put("inspect_available", true); result.put("decompile_available", false);
        if (!Files.isRegularFile(jar)) { result.put("code", "JADX_MISSING"); return result; }
        Path log = run == null ? Files.createTempFile("eta-reverse-probe-", ".log") : run.resolve("engine-version.log");
        try {
            String engineHash = sha256(jar);
            RunResult check = execute(List.of(javaExecutable(), "-Xmx128m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", jar.toString(), "jadx.cli.JadxCLI", "--version"), null, log, null, 20, 0);
            String version = tail(log, 1024).trim();
            boolean ready = check.exitCode == 0 && !check.timedOut && !check.limited && !version.isEmpty();
            result.put("status", ready ? "ready" : "unavailable"); result.put("ok", ready);
            result.put("decompile_available", ready); result.put("version_exit_code", check.exitCode);
            result.put("version_timed_out", check.timedOut); result.put("engine_version", version);
            result.put("jar_sha256", engineHash); result.put("code", ready ? "OK" : "JADX_PROBE_FAILED");
        } finally { if (run == null) Files.deleteIfExists(log); }
        return result;
    }

    static RunResult execute(List<String> argv, Path cwd, Path log, Path output, int seconds, long outputLimit) throws Exception {
        Path state = Files.createTempDirectory(log.toAbsolutePath().getParent(), "eta-reverse-engine-");
        Process process = null;
        try {
            List<String> scoped = new ArrayList<>(argv);
            scoped.add(1, "-Duser.home=" + state);
            ProcessBuilder builder = new ProcessBuilder(scoped).redirectErrorStream(true).redirectOutput(log.toFile());
            if (cwd != null) builder.directory(cwd.toFile());
            for (String ambient : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) builder.environment().remove(ambient);
            for (String key : List.of("APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME")) {
                Path directory = state.resolve(key.toLowerCase(Locale.ROOT)); Files.createDirectory(directory);
                builder.environment().put(key, directory.toString());
            }
            process = builder.start(); ownedChild = process;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            boolean timedOut = false, limited = false;
            long budget = output == null ? 16L * 1024 * 1024 : outputLimit;
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) { timedOut = true; break; }
                if ((Files.exists(log) && Files.size(log) > MAX_LOG) || outputBytes(state) + (output == null ? 0 : outputBytes(output)) > budget) { limited = true; break; }
            }
            if (timedOut || limited) stop(process);
            if (!process.waitFor(5, TimeUnit.SECONDS)) { stop(process); throw new Problem("PROCESS_NOT_STOPPED", "Owned analysis process did not stop"); }
            if (Files.exists(log) && Files.size(log) > MAX_LOG) limited = true;
            if (outputBytes(state) + (output == null ? 0 : outputBytes(output)) > budget) limited = true;
            return new RunResult(process.exitValue(), timedOut, limited);
        } finally {
            if (process != null && process.isAlive()) stop(process);
            ownedChild = null;
            if (state.getFileName().toString().startsWith("eta-reverse-engine-")) {
                try (Stream<Path> stream = Files.walk(state)) {
                    for (Path file : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException ignored) {}
            }
        }
    }

    static long outputBytes(Path directory) throws IOException, Problem {
        if (!Files.exists(directory)) return 0;
        long total = 0; int count = 0;
        try (Stream<Path> stream = Files.walk(directory)) {
            Iterator<Path> files = stream.iterator();
            while (files.hasNext()) {
                Path path = files.next();
                if (++count > MAX_FILES) throw new Problem("OUTPUT_FILE_LIMIT", "Analysis generated too many files");
                if (Files.isSymbolicLink(path)) throw new Problem("OUTPUT_LINK", "Analysis output contains a symbolic link");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    static List<Map<String,Object>> artifacts(Path directory, Path root, long limit) throws Exception {
        if (outputBytes(directory) > limit) throw new Problem("OUTPUT_LIMIT", "Analysis output exceeds its disk budget");
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
        List<Map<String,Object>> result = new ArrayList<>();
        for (Path file : files) {
            if (!file.toRealPath().startsWith(directory.toRealPath())) throw new Problem("OUTPUT_ESCAPE", "Analysis output left its owned directory");
            result.add(obj("path", root.relativize(file).toString(), "bytes", Files.size(file), "sha256", sha256(file)));
        }
        return result;
    }

    static boolean removeOwnedOutput(Path run) {
        Path output = run.resolve("decompiled").normalize();
        if (!output.getParent().equals(run.normalize())) return false;
        if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return true;
        try (Stream<Path> stream = Files.walk(output)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            return !Files.exists(output, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) { return false; }
    }

    static void trimLog(Path log, long budget) throws IOException {
        if (!Files.exists(log) || Files.size(log) <= budget) return;
        byte[] bytes = new byte[(int)budget];
        try (RandomAccessFile source = new RandomAccessFile(log.toFile(), "r")) {
            source.seek(source.length() - budget); source.readFully(bytes);
        }
        Files.write(log, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        try { process.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (Throwable ignored) {} }); } catch (Throwable ignored) {}
        try { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } catch (Throwable ignored) {}
    }

    static Path jadxJar() {
        String configured = System.getProperty("eta.reverse.jadx");
        if (configured == null || configured.isBlank()) configured = System.getenv("ETA_REVERSE_JADX_JAR");
        if (configured == null || configured.isBlank()) configured = "/opt/eta/apk-analysis/current/jadx/lib/jadx-1.5.6-all.jar";
        return Path.of(configured).toAbsolutePath().normalize();
    }
    static String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString(); }
    static int heapMiB() { try { return Math.max(128, Math.min(2048, Integer.parseInt(System.getenv().getOrDefault("ETA_REVERSE_JAVA_HEAP_MB", "768")))); } catch (NumberFormatException ignored) { return 768; } }
    static String required(Map<String,String> options, String key) throws Problem { String value = options.get(key); if (value == null || value.isBlank()) throw new Problem("BAD_ARGUMENTS", "Missing " + key); return value; }
    static int integer(Map<String,String> options, String key, int fallback, int min, int max) throws Problem {
        try { int value = options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback; if (value < min || value > max) throw new NumberFormatException(); return value; }
        catch (NumberFormatException bad) { throw new Problem("BAD_ARGUMENTS", key + " is outside its supported bounds"); }
    }
    static boolean validClass(String value) {
        if (value.isBlank() || value.length() > 512) return false;
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))) return false;
            for (int offset = Character.charCount(part.codePointAt(0)); offset < part.length();) { int code = part.codePointAt(offset); if (!Character.isJavaIdentifierPart(code)) return false; offset += Character.charCount(code); }
        }
        return true;
    }
    static String sha256(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream source = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = source.read(buffer)) >= 0) hash.update(buffer, 0, count); }
        return hex(hash.digest());
    }
    static String hex(byte[] value) { StringBuilder output = new StringBuilder(); for (byte item : value) output.append(String.format(Locale.ROOT, "%02x", item & 255)); return output.toString(); }
    static String tail(Path file, int budget) throws IOException {
        if (!Files.exists(file)) return "";
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) { long length = input.length(); input.seek(Math.max(0, length - budget)); byte[] bytes = new byte[(int)Math.min(length, budget)]; input.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8); }
    }
    static void writeReport(Path report, Map<String,Object> value) throws IOException {
        Path temporary = report.resolveSibling("report.json.writing");
        Files.writeString(temporary, json(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING); }
    }
    static Map<String,Object> base(String status, boolean ok) { return obj("protocol", PROTOCOL, "tool", "eta-reverse", "version", VERSION, "status", status, "ok", ok); }
    static Map<String,Object> obj(Object... pairs) { Map<String,Object> value = new LinkedHashMap<>(); for (int index = 0; index < pairs.length; index += 2) value.put((String)pairs[index], pairs[index + 1]); return value; }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?,?> map) { StringJoiner out = new StringJoiner(",", "{", "}"); map.forEach((key,item) -> out.add(quote(String.valueOf(key)) + ":" + json(item))); return out.toString(); }
        if (value instanceof Iterable<?> items) { StringJoiner out = new StringJoiner(",", "[", "]"); for (Object item : items) out.add(json(item)); return out.toString(); }
        return quote(String.valueOf(value));
    }
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) { char item = text.charAt(index); switch (item) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (item < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)item)); else out.append(item); }
        }}
        return out.append('"').toString();
    }
    static final class Problem extends Exception { final String code; Problem(String code, String message) { super(message); this.code = code; } }
    static final class RunResult { final int exitCode; final boolean timedOut, limited; RunResult(int exitCode, boolean timedOut, boolean limited) { this.exitCode=exitCode; this.timedOut=timedOut; this.limited=limited; } }
}
