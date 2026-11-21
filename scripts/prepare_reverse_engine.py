#!/usr/bin/env python3
"""Acquire only the APK profile's pinned official JADX archive for CI/host validation."""
from pathlib import Path
import argparse, hashlib, json, os, urllib.parse, urllib.request, zipfile
VERSION='1.5.6'
SHA256='545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974'
BYTES=72646741
API='https://api.github.com/repos/skylot/jadx'

class ScopedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        follow=super().redirect_request(req,fp,code,msg,headers,newurl)
        if follow is not None and urllib.parse.urlsplit(newurl).hostname!='api.github.com':follow.remove_header('Authorization')
        return follow


def prepare(folder):
    folder.mkdir(parents=True,exist_ok=True)
    archive=folder/f'jadx-{VERSION}.zip'
    token=os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    headers={'User-Agent':'Eta-Ultra-pinned-reverse-validation','Accept':'application/vnd.github+json'}
    if token:headers['Authorization']='Bearer '+token
    opener=urllib.request.build_opener(ScopedRedirect())
    if not archive.exists() or archive.stat().st_size!=BYTES or hashlib.sha256(archive.read_bytes()).hexdigest()!=SHA256:
        with opener.open(urllib.request.Request(API+'/releases/tags/v'+VERSION,headers=headers),timeout=60) as response:release=json.load(response)
        asset=next(a for a in release['assets'] if a['name']==f'jadx-{VERSION}.zip')
        if asset['size']!=BYTES or (asset.get('digest') and asset['digest']!='sha256:'+SHA256):raise RuntimeError('Pinned JADX release identity changed')
        binary_headers=dict(headers,Accept='application/octet-stream')
        partial=archive.with_name(archive.name+'.partial')
        try:
            with opener.open(urllib.request.Request(API+'/releases/assets/'+str(asset['id']),headers=binary_headers),timeout=180) as source,partial.open('wb') as output:
                size=0;digest=hashlib.sha256()
                while True:
                    block=source.read(1024*1024)
                    if not block:break
                    size+=len(block)
                    if size>BYTES:raise RuntimeError('JADX download exceeded pinned size')
                    output.write(block);digest.update(block)
            if size!=BYTES or digest.hexdigest()!=SHA256:raise RuntimeError('JADX download checksum mismatch')
            partial.replace(archive)
        finally:
            if partial.exists():partial.unlink()
    jar=folder/f'jadx-{VERSION}-all.jar'
    with zipfile.ZipFile(archive) as source:
        payload=source.read(f'lib/jadx-{VERSION}-all.jar')
    if not jar.exists() or jar.read_bytes()!=payload:jar.write_bytes(payload)
    record={'version':VERSION,'archiveSha256':SHA256,'archiveBytes':BYTES,'jar':str(jar.resolve()),'jarSha256':hashlib.sha256(payload).hexdigest(),
            'source':'https://github.com/skylot/jadx/releases/tag/v'+VERSION}
    (folder/'engine-verification.json').write_text(json.dumps(record,indent=2)+'\n','utf-8')
    return record

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-dir',type=Path,default=Path('build/reverse-engine'))
    args=parser.parse_args();print(json.dumps(prepare(args.cache_dir)))
