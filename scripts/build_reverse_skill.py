#!/usr/bin/env python3
"""Build and verify a deterministic, optional ETA Skill ZIP; no engine JAR is bundled."""
from pathlib import Path
import argparse, hashlib, json, zipfile
ROOT=Path(__file__).resolve().parents[1]
ID='eta-reverse-analysis'
NAME='Eta-Reverse-Analysis.skill.zip'


def build(output):
    inputs={
        'SKILL.md':ROOT/'plugins/eta-reverse-analysis/SKILL.md',
        'README.md':ROOT/'plugins/eta-reverse-analysis/README.md',
        'LICENSE':ROOT/'LICENSE',
        'assets/EtaReverse.java':ROOT/'app/src/main/assets/reverse/EtaReverse.java',
    }
    files={}
    for name,path in inputs.items():
        if path.is_symlink() or not path.is_file():raise RuntimeError('Package source is not an ordinary file: '+name)
        files[name]=path.read_text('utf-8').encode('utf-8')
    skill=files['SKILL.md'].decode('utf-8')
    assert skill.startswith('---\n') and '\nname: '+ID+'\n' in skill
    assert 'environment=linux' in skill and 'eta-reverse probe' in skill
    assert len(skill)<6000,'Skill slash-command injection budget exceeded'
    metadata={'id':ID,'moduleVersion':'0.1.0','apkAnalysisRevision':2,'sourceLauncherMinimumJdk':17,
              'runtimeJdkProvidedByProfile':25,'externalJadxVersion':'1.5.6','optionalUserInstall':True,
              'runtimeCommands':['probe','inspect','decompile'],'engineBundled':False,
              'license':'PolyForm-Noncommercial-1.0.0',
              'files':{name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for name,data in files.items()}}
    files['package-info.json']=(json.dumps(metadata,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode('utf-8')
    output.parent.mkdir(parents=True,exist_ok=True)
    temporary=output.with_name(output.name+'.writing')
    with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data in sorted(files.items()):
            entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.create_system=3
            entry.external_attr=(0o100644<<16);entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
    with zipfile.ZipFile(temporary) as archive:
        assert archive.testzip() is None
        assert set(archive.namelist())==set(files)
        assert sum(entry.file_size for entry in archive.infolist())<128*1024*1024
        assert len(archive.namelist())<2048 and temporary.stat().st_size<32*1024*1024
        for name,data in files.items():assert archive.read(name)==data
    temporary.replace(output)
    record={'file':output.name,'bytes':output.stat().st_size,'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),**metadata}
    output.with_name('Eta-Reverse-Analysis-info.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n','utf-8')
    return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'dist'/NAME)
    parser.add_argument('--checksums',type=Path)
    args=parser.parse_args()
    record=build(args.output)
    if args.checksums:
        managed={args.output.name,'Eta-Reverse-Analysis-info.json'}
        validation=args.output.parent/'reverse-smoke.json'
        if validation.exists():
            witness=json.loads(validation.read_text('utf-8'))
            assert witness.get('passed') and witness.get('fixtureActuallyDecompiled')
            assert witness.get('helperSourceSha256')==record['files']['assets/EtaReverse.java']['sha256']
            managed.add(validation.name)
        existing=args.checksums.read_text('utf-8').splitlines() if args.checksums.exists() else []
        retained=[line for line in existing if line.split() and line.split()[-1] not in managed]
        for name in sorted(managed):
            path=args.output.parent/name
            retained.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+name)
        args.checksums.write_text('\n'.join(retained)+'\n','utf-8')
    print(json.dumps(record,ensure_ascii=False))
