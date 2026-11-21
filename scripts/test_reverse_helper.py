#!/usr/bin/env python3
"""Hermetic CLI contract tests. The fake engine is deliberately not a live JADX proof."""
from pathlib import Path
import hashlib, json, os, shutil, signal, struct, subprocess, tempfile, unittest, zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/assets/reverse/EtaReverse.java'
FAKE_ENGINE = r'''
package jadx.cli;
import java.nio.file.*;
public class JadxCLI {
  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--version")) { System.out.println("9.9.9-fixture"); return; }
    String mode = System.getenv().getOrDefault("ETA_REVERSE_TEST_ENGINE_MODE", "normal");
    if (mode.equals("timeout")) { Thread.sleep(120000); return; }
    if (mode.equals("fail")) { System.err.println("fixture failure"); System.exit(7); }
    if (mode.equals("empty")) return;
    Path output = null;
    for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("-d")) output = Path.of(args[i+1]);
    if (output == null) throw new IllegalArgumentException("no output");
    Path file = output.resolve("sources/fixture/Example.java"); Files.createDirectories(file.getParent());
    if (mode.equals("oversize")) Files.writeString(file, "x".repeat(3 * 1024 * 1024));
    else Files.writeString(file, "package fixture; public class Example { public int answer() { return 42; } }");
    if (mode.equals("state")) Files.writeString(output.resolve("engine-home.txt"), System.getProperty("user.home"));
    if (mode.equals("mutate")) Files.writeString(Path.of(args[args.length-1]), "changed-owned-snapshot");
    if (mode.equals("partial")) { System.err.println("fixture partial"); System.exit(2); }
  }
}
'''


def java_environment():
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
        env.pop(name, None)
    return env


class ReverseHelperContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix='eta-reverse-contract-')
        cls.build_root = Path(cls.build.name)
        configured = os.environ.get('JAVA_HOME')
        compiler = Path(configured) / 'bin/javac' if configured else Path(shutil.which('javac') or '')
        if os.name == 'nt' and compiler.suffix != '.exe': compiler = compiler.with_suffix('.exe')
        if not compiler.is_file(): raise RuntimeError('A full JDK is required for reverse helper contract tests')
        cls.jdk = compiler.resolve().parent
        cls.java = cls.jdk / ('java.exe' if os.name == 'nt' else 'java')
        jar_tool = cls.jdk / ('jar.exe' if os.name == 'nt' else 'jar')
        cls.classes = cls.build_root / 'classes'; cls.classes.mkdir()
        fixture = cls.build_root / 'JadxCLI.java'; fixture.write_text(FAKE_ENGINE, 'utf-8')
        wrong = cls.build_root / 'FixtureWrongMain.java'
        wrong.write_text('public class FixtureWrongMain { public static void main(String[] args) { System.err.println("wrong GUI-style manifest entry"); System.exit(99); } }', 'utf-8')
        for command in ([str(compiler), '--release', '17', '-encoding', 'UTF-8', '-d', str(cls.classes), str(SOURCE)],
                        [str(compiler), '--release', '17', '-encoding', 'UTF-8', '-d', str(cls.classes), str(fixture), str(wrong)]):
            result = subprocess.run(command, capture_output=True, env=java_environment(), timeout=60)
            if result.returncode: raise RuntimeError(result.stderr.decode('utf-8', 'replace'))
        cls.jar = cls.build_root / 'fixture-jadx.jar'
        result = subprocess.run([str(jar_tool), '--create', '--file', str(cls.jar), '--main-class', 'FixtureWrongMain',
                                 '-C', str(cls.classes), 'FixtureWrongMain.class', '-C', str(cls.classes), 'jadx/cli/JadxCLI.class'], capture_output=True, env=java_environment(), timeout=30)
        if result.returncode: raise RuntimeError(result.stderr.decode('utf-8', 'replace'))

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.area = tempfile.TemporaryDirectory(prefix='eta-reverse-sample-')
        self.addCleanup(self.area.cleanup)
        self.base = Path(self.area.name)
        self.workspace = self.base / "workspace 名 ';$"
        self.workspace.mkdir()
        self.apk = self.workspace / "sample 名 ';$.apk"
        self.make_apk(self.apk)

    @staticmethod
    def make_apk(path):
        header = bytearray(112); header[:8] = b'dex\n039\0'
        struct.pack_into('<I', header, 32, 112); struct.pack_into('<I', header, 36, 112)
        struct.pack_into('<I', header, 40, 0x12345678); struct.pack_into('<I', header, 96, 1)
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('AndroidManifest.xml', '<manifest package="fixture"/>')
            archive.writestr('classes.dex', header)
            archive.writestr('lib/arm64-v8a/libfixture.so', b'fixture-not-an-elf')

    def call(self, *arguments, mode='normal', jar=None, process_timeout=60):
        chosen = self.jar if jar is None else jar
        command = [str(self.java), '-Deta.reverse.jadx='+str(chosen), '-cp', str(self.classes), 'EtaReverse', *map(str, arguments)]
        env = java_environment(); env['ETA_REVERSE_TEST_ENGINE_MODE'] = mode
        kwargs = {'stdout':subprocess.PIPE, 'stderr':subprocess.PIPE, 'env':env}
        if os.name == 'nt': kwargs['creationflags'] = subprocess.CREATE_NEW_PROCESS_GROUP
        else: kwargs['start_new_session'] = True
        process = subprocess.Popen(command, **kwargs)
        try:
            stdout, stderr = process.communicate(timeout=process_timeout)
        except subprocess.TimeoutExpired:
            if os.name == 'nt':
                subprocess.run(['taskkill','/pid',str(process.pid),'/t','/f'], capture_output=True)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(); self.fail('Owned helper process did not finish within the test deadline')
        self.assertLess(len(stdout), 16000, 'CLI summary must fit the existing terminal result budget')
        self.assertEqual(len(stdout.decode('utf-8').strip().splitlines()), 1)
        body = json.loads(stdout)
        self.assertEqual(body['protocol'], 'eta.reverse.v1')
        return process.returncode, body

    def analyze(self, mode='normal', *extra, command='decompile'):
        return self.call(command, '--workspace', self.workspace, '--input', self.apk, *extra, mode=mode)

    def report(self, result):
        path = Path(result['report_path'])
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), result['report_sha256'])
        self.assertTrue(path.resolve().is_relative_to(self.workspace.resolve()))
        return json.loads(path.read_text('utf-8'))

    def test_probe_reports_actual_version_and_missing_engine_is_not_ready(self):
        code, result = self.call('probe')
        self.assertEqual(code, 0); self.assertTrue(result['ok'])
        self.assertEqual(result['engine_version'], '9.9.9-fixture')
        code, missing = self.call('probe', jar=self.base/'missing.jar')
        self.assertNotEqual(code, 0); self.assertFalse(missing['decompile_available'])
        self.assertEqual(missing['code'], 'JADX_MISSING')

    def test_inspection_reports_input_fingerprint_without_executing_or_retaining_it(self):
        original = self.apk.read_bytes()
        code, result = self.analyze(command='inspect')
        self.assertEqual(code, 0); self.assertEqual(result['status'], 'inspected')
        self.assertEqual(hashlib.sha256(original).hexdigest(), result['input_sha256'])
        report = self.report(result)
        self.assertEqual(report['inspection']['declared_class_definitions'], 1)
        self.assertEqual(report['inspection']['native_abis'], ['arm64-v8a'])
        self.assertFalse(report['target_execution']); self.assertFalse(report['inspection']['signature_verified'])
        self.assertFalse((Path(result['report_path']).parent/'input.apk').exists())
        self.assertEqual(original, self.apk.read_bytes())

    def test_success_requires_nonempty_artifacts_and_real_hashes(self):
        code, result = self.analyze('normal', '--class', 'fixture.Example')
        self.assertEqual(code, 0); self.assertEqual(result['status'], 'completed')
        report = self.report(result)
        self.assertEqual(report['engine']['engine_version'], '9.9.9-fixture')
        self.assertEqual(report['exit_code'], 0)
        self.assertEqual(report['artifact_count'], 1)
        artifact = report['artifacts'][0]
        file = self.workspace / artifact['path']
        self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), artifact['sha256'])
        self.assertIn('return 42', file.read_text('utf-8'))

    def test_empty_exit_zero_and_partial_exit_nonzero_are_not_completion(self):
        for mode, expected in [('empty','failed'),('partial','partial'),('fail','failed')]:
            with self.subTest(mode=mode):
                code, result = self.analyze(mode)
                self.assertNotEqual(code, 0); self.assertFalse(result['ok'])
                self.assertEqual(result['status'], expected)
                self.report(result)

    def test_input_mutation_is_detected_and_original_remains_unchanged(self):
        original = self.apk.read_bytes()
        code, result = self.analyze('mutate')
        self.assertNotEqual(code, 0); self.assertEqual(result['code'], 'INPUT_CHANGED')
        self.assertEqual(original, self.apk.read_bytes())
        self.assertFalse((Path(result['report_path']).parent/'input.apk').exists())

    def test_output_disk_limit_removes_owned_generated_files(self):
        code, result = self.analyze('oversize', '--max-output-mb', '1')
        self.assertNotEqual(code, 0); self.assertFalse(result['ok'])
        self.assertEqual(result['code'], 'OUTPUT_LIMIT')
        report = self.report(result)
        self.assertTrue(report['limited_output_removed'])
        self.assertFalse((Path(result['report_path']).parent/'decompiled').exists())

    def test_engine_configuration_is_task_owned_and_removed_after_use(self):
        code, result = self.analyze('state')
        self.assertEqual(code, 0)
        self.report(result)
        run = Path(result['report_path']).parent
        home = Path((run/'decompiled/engine-home.txt').read_text('utf-8'))
        self.assertTrue(home.is_relative_to(run))
        self.assertTrue(home.name.startswith('eta-reverse-engine-'))
        self.assertFalse(home.exists())
        self.assertFalse(any(path.name.startswith('eta-reverse-engine-') for path in run.iterdir()))

    def test_timeout_is_failure_and_owned_process_stops(self):
        code, result = self.analyze('timeout', '--timeout-seconds', '15')
        self.assertNotEqual(code, 0); self.assertEqual(result['status'], 'timed_out')
        self.assertTrue(result['timed_out']); self.assertEqual(result['code'], 'TIMEOUT')
        self.report(result)

    def test_workspace_escape_and_invalid_class_or_arguments_are_rejected(self):
        outside = self.base / 'outside.apk'; self.make_apk(outside)
        code, result = self.call('inspect','--workspace',self.workspace,'--input',outside)
        self.assertNotEqual(code,0); self.assertEqual(result['code'],'OUTSIDE_WORKSPACE')
        for extra in [('--class','fixture.Example;touch-owned'),('--timeout-seconds','0'),('--max-output-mb','999'),('--free-command','anything')]:
            with self.subTest(extra=extra):
                code, result = self.analyze('normal', *extra)
                self.assertNotEqual(code,0); self.assertFalse(result['ok'])
                self.assertEqual(result['code'],'BAD_CLASS' if extra[0]=='--class' else 'BAD_ARGUMENTS')

    def test_non_apk_and_oversized_input_fail_without_modifying_source(self):
        bad = self.workspace/'not-apk.zip'
        with zipfile.ZipFile(bad,'w') as archive: archive.writestr('hello.txt','fixture')
        code, result = self.call('inspect','--workspace',self.workspace,'--input',bad)
        self.assertNotEqual(code,0); self.assertEqual(result['code'],'NOT_AN_APK')
        large = self.workspace/'large.apk'
        with large.open('wb') as file: file.truncate(256*1024*1024+1)
        code, result = self.call('inspect','--workspace',self.workspace,'--input',large)
        self.assertNotEqual(code,0); self.assertEqual(result['code'],'INPUT_LIMIT')
        self.assertEqual(large.stat().st_size,256*1024*1024+1)

    def test_repeated_runs_are_separate_and_reports_are_valid_utf8_json(self):
        first_code, first = self.analyze(command='inspect')
        second_code, second = self.analyze(command='inspect')
        self.assertEqual((first_code,second_code),(0,0))
        self.assertNotEqual(first['task_id'],second['task_id'])
        self.report(first); self.report(second)


if __name__=='__main__':
    unittest.main(verbosity=2)
