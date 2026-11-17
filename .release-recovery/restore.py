import base64,hashlib,json,os,pathlib,subprocess,urllib.request
root=pathlib.Path.cwd()
def run(*args,**kw):return subprocess.check_output(['git',*args],**kw)
p=json.loads((root/'.release-recovery/final.json').read_text())
model=root/'app/src/main/assets/offline-asr/vosk-model-small-cn-0.22.zip'
model.parent.mkdir(parents=True,exist_ok=True)
with urllib.request.urlopen('https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip',timeout=120) as response,model.open('wb') as out:
 while True:
  part=response.read(1024*1024)
  if not part:break
  out.write(part)
b=model.read_bytes()
assert len(b)==43898754 and hashlib.sha256(b).hexdigest()=='3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba'
workflow=root/'.github/workflows/android-release.yml'
workflow.write_bytes(base64.b64decode(p['workflow']))
run('read-tree',p['parent'])
for e in p['changes']:
 if e['deleted']:
  run('update-index','--force-remove','--',e['path'])
 else:
  f=root/e['path'];actual=run('hash-object','-w','--',str(f)).decode().strip()
  assert actual==e['sha'],(e['path'],actual,e['sha'])
  run('update-index','--add','--cacheinfo',e['mode'],actual,e['path'])
actual_tree=run('write-tree').decode().strip();assert actual_tree==p['tree'],(actual_tree,p['tree'])
commit=run('hash-object','-t','commit','-w','--stdin',input=base64.b64decode(p['commit_raw'])).decode().strip()
assert commit==p['commit']
remote=run('ls-remote','origin','refs/heads/main').decode().split()[0]
assert remote in (p['parent'],p['commit']),remote
subprocess.run(['git','push','origin',commit+':refs/heads/main'],check=True)
print('SOURCE_PUBLISHED',commit,'TREE',actual_tree)
