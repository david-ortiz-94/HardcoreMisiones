#!/usr/bin/env python3
"""Reproducibility rehearsal (not a candidate release). Java 25, pinned input jars.
Compiles only production sources twice into separate directories, packages them
in deterministic ZIP/JAR order, and inspects the exact archive contents.
No JAR is written outside build/; release acceptance remains a separate gate.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, time, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
RESOURCES = {'catalogo.yml','config.yml','lotes-jefes.yml','perfiles-f2.yml','plugin.yml','premios-jefes.yml','retos-f3.yml'}

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()

def verify_dependencies(entries, expected):
    # Preserve order: duplicate class resolution depends on classpath ordering.
    actual = [{'name':p.name,'sha256':sha(p)} for p in entries]
    if actual != expected:
        raise ValueError('Classpath names, hashes or order differ from the approved offline lock')
    if len({p.name for p in entries}) != len(entries):
        raise ValueError('Duplicate dependency names')
    return actual

def archive(target, compiled, resources, version):
    resource_files={p.name:p for p in resources.iterdir() if p.is_file()}
    if set(resource_files)!=RESOURCES or any(p.is_dir() for p in resources.iterdir()):
        raise ValueError('Unexpected resource set')
    entries = {p.relative_to(compiled).as_posix():p.read_bytes() for p in compiled.rglob('*.class')}
    if not entries or any(not name.startswith('com/hardcoremisiones/') for name in entries):
        raise ValueError('Unexpected compiled class namespace')
    if any(re.search(r'(?:Test|Harness|Fake)(?:\$[^/]*)?\.class$',name) for name in entries):
        raise ValueError('Test, harness or fake in production archive')
    entries.update({k:p.read_bytes() for k,p in resource_files.items()})
    descriptor=entries['plugin.yml'].decode('utf8')
    if not re.search(r'^version:\s*[\'\"]?'+re.escape(version)+r'[\'\"]?\s*$',descriptor,re.M):
        raise ValueError('Descriptor version mismatch')
    if 'main: com.hardcoremisiones.HardcoreMisiones' not in descriptor:
        raise ValueError('Unexpected plugin entrypoint')
    entries['META-INF/MANIFEST.MF']=(f'Manifest-Version: 1.0\r\nImplementation-Version: {version}\r\nImplementation-Status: DEVELOPMENT ONLY - NOT A RELEASE\r\n\r\n').encode('ascii')
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.create_system=3;info.external_attr=0o100644<<16
            z.writestr(info,data,compresslevel=9)
    with zipfile.ZipFile(target) as z:
        if z.testzip() is not None or len(z.namelist())!=len(entries) or set(z.namelist())!=set(entries):
            raise ValueError('Archive integrity failure')
        if any(z.read(k)!=v for k,v in entries.items()):raise ValueError('Archive content mismatch')
    return {'bytecode_entries':sum(n.endswith('.class') for n in entries),'resource_entries':len(RESOURCES),'bytes':target.stat().st_size,'sha256':sha(target)}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home',required=True,type=Path);p.add_argument('--classpath',required=True)
    a=p.parse_args(); out=ROOT/'build/e2-rehearsal';out.mkdir(parents=True,exist_ok=True)
    entries=[Path(s).resolve(strict=True) for s in a.classpath.split(os.pathsep)]
    lock=json.loads((ROOT/'docs/OFFLINE_DEPENDENCIES_LOCK.json').read_text())
    deps=verify_dependencies(entries,lock['classpath'])
    javac=a.java_home/'bin/javac'; java=a.java_home/'bin/java'
    ver=subprocess.check_output([str(javac),'-version'],stderr=subprocess.STDOUT,text=True).strip()
    if not re.fullmatch(r'javac 25(?:[.\w+\-]*)',ver):raise ValueError('Java 25 required: '+ver)
    src=sorted((ROOT/'src/main/java').rglob('*.java'))
    resources=ROOT/'src/main/resources'
    fingerprint={p.relative_to(ROOT).as_posix():sha(p) for p in src+sorted(resources.iterdir())}
    builds=[]
    for n in (1,2):
        dest=out/f'compile-{n}'
        if dest.exists():shutil.rmtree(dest)
        dest.mkdir(); arg=out/f'sources-{n}.args'
        arg.write_text('\n'.join('"'+str(s)+'"' for s in src),encoding='utf8')
        start=time.monotonic()
        r=subprocess.run([str(javac),'--release','25','-g:source,lines','-encoding','UTF-8','-proc:none','-classpath',os.pathsep.join(map(str,entries)),'-d',str(dest),'@'+str(arg)],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        (out/f'compile-{n}.log').write_bytes(r.stdout)
        if r.returncode:raise RuntimeError(f'Compile {n} failed ({r.returncode})')
        elapsed=time.monotonic()-start
        result=archive(out/f'INTERNAL-NOT-INSTALLABLE-{n}.jar',dest,resources,'2.1.0-dev');result['compile_seconds']=round(elapsed,3);builds.append(result)
    if any(sha(ROOT/p)!=h for p,h in fingerprint.items()):raise RuntimeError('Source changed during rehearsal')
    identical=builds[0]['sha256']==builds[1]['sha256']
    report={'status':'PASS' if identical else 'FAIL','scope':'internal development archive only; not a candidate; Gradle not executed','javac':ver,'python':sys.version,'classpath':deps,'source_sha256':fingerprint,'builds':builds,'byte_identical':identical}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'byte_identical':identical,'builds':builds},indent=2))
    return 0 if identical else 1

if __name__=='__main__':sys.exit(main())
