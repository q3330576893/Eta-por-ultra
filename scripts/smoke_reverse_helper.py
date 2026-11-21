#!/usr/bin/env python3
"""Real JADX smoke on a self-built APK; separate from synthetic engine contract tests."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, signal, subprocess, sys, zipfile

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--jadx-jar',type=Path,required=True)
parser.add_argument('--sdk',type=Path,required=True)
parser.add_argument('--workspace',type=Path,required=True)
parser.add_argument('--published-apk',type=Path)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
args.workspace.mkdir(parents=True,exist_ok=True)
workspace=args.workspace.resolve()
java_home=Path(os.environ['JAVA_HOME'])
exe=lambda name: java_home/'bin'/(name+'.exe' if os.name=='nt' else name)
java=exe('java');javac=exe('javac')
source=ROOT/'app/src/main/assets/reverse/EtaReverse.java'

def command(argv,timeout=120):
    env=dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'):env.pop(key,None)
    options={'stdout':subprocess.PIPE,'stderr':subprocess.PIPE,'env':env}
    if os.name=='nt':options['creationflags']=subprocess.CREATE_NEW_PROCESS_GROUP
    else:options['start_new_session']=True
    process=subprocess.Popen([str(v) for v in argv],**options)
    try:out,error=process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        if os.name=='nt':subprocess.run(['taskkill','/pid',str(process.pid),'/t','/f'],capture_output=True)
        else:os.killpg(process.pid,signal.SIGKILL)
        process.communicate();raise RuntimeError('Owned smoke subprocess exceeded deadline')
    if process.returncode:raise RuntimeError('Smoke subprocess failed: '+error.decode('utf-8','replace')[-1500:]+' '+out.decode('utf-8','replace')[-1500:])
    return out

def helper(*options):
    result=json.loads(command([java,'-Deta.reverse.jadx='+str(args.jadx_jar.resolve()),source,*options],timeout=210))
    assert result['ok'],result
    return result

build=workspace/'fixture-build';build.mkdir(exist_ok=True)
classes=build/'classes';classes.mkdir(exist_ok=True)
java_source=build/'EtaSample.java'
java_source.write_text('package io.eta.reverse.fixture; public final class EtaSample { public static int answer() { return 42; } }','utf-8')
command([javac,'--release','17','-encoding','UTF-8','-d',classes,java_source])
sdk=args.sdk.resolve()
platform=sdk/'platforms/android-37.0/android.jar'
if not platform.is_file():platform=sdk/'platforms/android-37/android.jar'
assert platform.is_file(),'Android 37 platform required for real fixture'
tools=sdk/'build-tools/37.0.0'
dex=build/'dex';dex.mkdir(exist_ok=True)
command([java,'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','33','--lib',platform,'--output',dex,classes/'io/eta/reverse/fixture/EtaSample.class'])
manifest=build/'AndroidManifest.xml'
manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.eta.reverse.fixture"><uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37"/><application android:label="Eta Reverse Fixture"/></manifest>','utf-8')
apk=workspace/'fixture sample 名.apk'
aapt=tools/('aapt2.exe' if os.name=='nt' else 'aapt2')
command([aapt,'link','-I',platform,'--manifest',manifest,'-o',apk])
with zipfile.ZipFile(apk,'a',zipfile.ZIP_DEFLATED) as archive:archive.write(dex/'classes.dex','classes.dex')
probe=helper('probe')
inspection=helper('inspect','--workspace',workspace,'--input',apk)
assert inspection['input_sha256']==hashlib.sha256(apk.read_bytes()).hexdigest()
inspect_report=json.loads(Path(inspection['report_path']).read_text('utf-8'))
assert inspect_report['inspection']['declared_class_definitions']==1
result=helper('decompile','--workspace',workspace,'--input',apk,'--class','io.eta.reverse.fixture.EtaSample','--timeout-seconds','120','--max-output-mb','16')
report=json.loads(Path(result['report_path']).read_text('utf-8'))
assert report['engine']['engine_version'].strip()=='1.5.6'
assert result['status']=='completed' and report['exit_code']==0 and not report['timed_out']
assert not report['target_execution'] and not report['snapshot_retained']
assert report['input_sha256']==inspection['input_sha256']
artifacts=[]
for item in report['artifacts']:
    file=workspace/item['path']
    assert file.resolve().is_relative_to(workspace)
    assert hashlib.sha256(file.read_bytes()).hexdigest()==item['sha256']
    artifacts.append(file)
assert any('int answer()' in file.read_text('utf-8') and 'return 42;' in file.read_text('utf-8') for file in artifacts)
published=None
if args.published_apk:
    selected=workspace/'published-eta.apk'
    shutil.copyfile(args.published_apk,selected)
    published=helper('inspect','--workspace',workspace,'--input',selected)
    assert published['input_sha256']==hashlib.sha256(args.published_apk.read_bytes()).hexdigest()
record={'passed':True,'sourceCommit':os.environ.get('GITHUB_SHA'),'hostPlatform':sys.platform,'engine':'JADX','engineVersion':probe['engine_version'],'engineJarSha256':probe['jar_sha256'],
        'helperSourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'javaVersion':probe['java_version'],
        'fixtureInputSha256':inspection['input_sha256'],'knownClass':'io.eta.reverse.fixture.EtaSample',
        'knownMethod':'answer() returns 42','fixtureInspected':True,'fixtureActuallyDecompiled':True,
        'inputExecuted':False,'androidDeviceExecution':False,'result':result,'publishedApkInspection':published}
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2),'utf-8')
print(json.dumps(record,ensure_ascii=False))
