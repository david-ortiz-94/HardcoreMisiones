#!/usr/bin/env python3
"""Extract the exact locked compile dependencies from the supplied offline bundle.
Writes only to a new destination. No network, no plugin installation.
"""
import argparse, hashlib, io, json, os, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=Path);p.add_argument('--bosses',required=True,type=Path);p.add_argument('--destination',required=True,type=Path);a=p.parse_args()
lock=json.loads((ROOT/'docs/OFFLINE_DEPENDENCIES_LOCK.json').read_text())['classpath']
dest=a.destination.resolve()
if dest.exists() and (not dest.is_dir() or any(dest.iterdir())):p.error('Destination must be a new or empty directory')
with zipfile.ZipFile(a.bundle) as outer:
    with zipfile.ZipFile(io.BytesIO(outer.read('HM2_F1_JARS/HM2_Paper26_2_Dependencias_OFFLINE.zip'))) as libs, zipfile.ZipFile(io.BytesIO(outer.read('HM2_F1_JARS/paper-26.2-135.jar'))) as paper:
        verified=[]
        for row in lock:
            name=row['name']
            if Path(name).name!=name:raise ValueError('Invalid dependency name')
            if name=='paper-api-135.jar':data=paper.read('META-INF/libraries/io/papermc/paper/paper-api/26.2.build.135-stable/paper-api-26.2.build.135-stable.jar')
            elif name=='HardcoreBosses(1).jar':data=a.bosses.read_bytes()
            else:data=libs.read(name)
            if hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Hash mismatch: '+name)
            verified.append((name,data))
# Do not write anything if any supplied input failed verification.
dest.mkdir(parents=True,exist_ok=True)
for name,data in verified:
    with (dest/name).open('xb') as out:out.write(data)
(dest/'classpath.txt').write_text(os.pathsep.join(str(dest/name) for name,_ in verified),encoding='utf8')
print(f'{len(verified)} locked dependencies verified and extracted; classpath: {dest / "classpath.txt"}')
