"""Canonical inputs for JVM/Paper evidence correlation, not a signature."""
import hashlib, json, os, subprocess
from pathlib import Path


def sha(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source,'sha256').hexdigest()


def inputs(root, classpath, java_home):
    paths=[]
    for directory in ('src/main/java','src/main/resources','src/test/java','legacy/recuperacion_rc3_original/tests','legacy/recursos_rc3','fixtures','tools','scripts'):
        paths.extend(p for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    paths.extend(root/x for x in ('build.gradle.kts','settings.gradle.kts','gradle.properties') if (root/x).is_file())
    files={p.relative_to(root).as_posix():sha(p) for p in sorted(set(paths))}
    jars=[{'name':Path(p).name,'sha256':sha(p)} for p in classpath.split(os.pathsep)]
    version=subprocess.check_output([str(java_home/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True).strip()
    payload={'files':files,'classpath':jars,'java_version':version}
    payload['sha256']=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return payload
