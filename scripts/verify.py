#!/usr/bin/env python3
"""Compile Java 25 and inventory standalone test outcomes; never treat unrun tests as PASS."""
import argparse, json, os, re, subprocess, sys, shutil
from pathlib import Path
from evidence_inputs import inputs, sha
# Windows consoles and redirected Gradle output can have different encodings.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(errors="backslashreplace")
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java-home',required=True,type=Path);p.add_argument('--classpath',required=True);p.add_argument('--paper-log',type=Path);p.add_argument('--jvm-only',action='store_true',help='Run standalone JVM scope; report Paper and missing fake separately');a=p.parse_args();
if a.jvm_only and a.paper_log:p.error('--jvm-only and --paper-log are mutually exclusive')
root=Path(__file__).resolve().parents[1];out=root/('build/verification-jvm' if a.jvm_only else 'build/verification');out.mkdir(parents=True,exist_ok=True)
# Do not leave old success summaries visible after a failed compilation.
for stale in ('results.json','verification-inputs.json'):
    (out/stale).unlink(missing_ok=True)
fixture=root/'legacy/recursos_rc3/config.yml'
if not fixture.is_file():sys.exit('Missing exact RC3 config fixture: '+str(fixture))
fingerprint=inputs(root,a.classpath,a.java_home)
def run(cmd,log,timeout=180):
    target=out/log
    try:
        result=subprocess.run([str(c) for c in cmd],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
        target.write_bytes(result.stdout)
        return result.returncode
    except subprocess.TimeoutExpired as exc:
        target.write_bytes(exc.stdout or b'')
        return 124
    except OSError as exc:
        target.write_text(str(exc)+'\n',encoding='utf8')
        return 1

def java_args(path, args):
    # Both Java launchers interpret backslashes inside quoted argument files.
    # Escape literal paths and quotes; keep every argument separate (including spaces).
    def quote(value):
        return '"'+str(value).replace('\\','\\\\').replace('"','\\"')+'"'
    path.write_text('\n'.join(quote(value) for value in args)+'\n',encoding='utf8')
    return '@'+str(path)

def show_failure(tag, status):
    log=out/(tag+'.log')
    print(f'\n[{status}] {tag} | log: {log}',file=sys.stderr,flush=True)
    if log.is_file():
        diagnostic=log.read_text(encoding='utf8',errors='replace')
        print(diagnostic or '(sin salida del proceso)',file=sys.stderr,flush=True)
        if os.name=='nt' and ('createSymbolicLink' in diagnostic or 'createSymbolicLink0' in diagnostic):
            print('Windows: comprobar permisos para crear enlaces simbólicos (Modo de desarrollador o terminal elevada). No se omite esta prueba.',file=sys.stderr,flush=True)

def require_compile(rc, tag):
    if rc:
        show_failure(tag, f'COMPILATION_FAILED exit={rc}')
        sys.exit(f'{tag} failed (exit {rc}); full diagnostic: {out/(tag+".log")}')

javac=a.java_home/'bin/javac';java=a.java_home/'bin/java';classes=out/'main';tc=out/'test';
for directory in (classes,tc):
 if directory.exists():shutil.rmtree(directory)
 directory.mkdir()
main=sorted((root/'src/main/java').rglob('*.java'));test=sorted((root/'src/test/java').rglob('*.java'))+sorted((root/'legacy/recuperacion_rc3_original/tests').rglob('*.java'))
def compile_sources(paths,dest,cp,tag):
    f=out/(tag+'.args')
    args=java_args(f,['--release','25','-encoding','UTF-8','-cp',cp,'-d',dest,*paths])
    return run([javac,args],tag+'.log')
require_compile(compile_sources(main,classes,a.classpath,'main-compile'),'main-compile')
cp=os.pathsep.join([str(classes),a.classpath]);blocked=[]
# Official fake may be supplied by its provider; absence remains a release blocker.
import zipfile
has_fake=False
for entry in a.classpath.split(os.pathsep):
 try:
  with zipfile.ZipFile(entry) as z:has_fake|=any(n.startswith('com/hardcorebosses/fake/') for n in z.namelist())
 except (OSError,zipfile.BadZipFile):pass
if not has_fake:
 blocked=[x for x in test if 'com.hardcorebosses.fake' in x.read_text(encoding='utf8')];test=[x for x in test if x not in blocked]
require_compile(compile_sources(test,tc,cp,'test-compile'),'test-compile')
paper_suites=re.findall(r'suites\.put\("(\w+)"',(root/'tools/PaperContractHarness.java').read_text(encoding='utf8'))
all_test_names=[x.stem for x in test+blocked if x.name.endswith('Test.java')]
if not all_test_names or len(paper_suites)!=len(set(paper_suites)) or any(all_test_names.count(name)!=1 for name in paper_suites):sys.exit('Missing or ambiguous test inventory / Paper suites')
results=[{'file':str(x.relative_to(root)),'status':'BLOCKED_FAKE_MISSING'} for x in blocked];runtime=os.pathsep.join([str(tc),str(root/'src/main/resources'),cp]);cfg=str(root/'src/main/resources/config.yml')
for x in test:
 if not x.name.endswith('Test.java'):continue # historical support is compiled, not counted as a test
 s=x.read_text(encoding='utf8');pkg=re.search(r'\bpackage\s+([\w.]+);',s);name=(pkg.group(1)+'.' if pkg else '')+x.stem
 if a.jvm_only and x.stem in paper_suites:status='PENDING_PAPER'
 elif not re.search(r'public\s+static\s+void\s+main\s*\(',s):status='NO_MAIN'
 else:
  extra=[cfg] if x.stem in {'ConfigRecargaRC6Test','F4ConfiguracionProduccionTest','F4RC4MigracionSelectorTest','F4RC5ConfigMigracionTest'} else []
  if x.stem=='F4ConfiguracionProduccionTest':extra.append(str(root/'legacy/recursos_rc3/config.yml'))
  args=java_args(out/(x.stem+'.args'),['-ea','-Xmx192m','-cp',runtime,name,*extra])
  rc=run([java,args],x.stem+'.log',30);status='PASS' if rc==0 else 'TIMEOUT' if rc==124 else 'FAIL'
 row={'file':str(x.relative_to(root)),'name':name,'status':status}
 if status in {'PASS','FAIL','TIMEOUT'}:
  row['exit_code']=rc;row['log']=(out/(x.stem+'.log')).relative_to(root).as_posix()
 results.append(row)
 if status=='NO_MAIN':print(f'[NO_MAIN] {row["file"]}',file=sys.stderr,flush=True)
# A complete, explicitly supplied lab log is a separate execution environment.
# Never infer PASS from a missing main or an exception outside Paper.
if a.paper_log:
 metadata=json.loads(a.paper_log.with_name('paper-inputs.json').read_text())
 if (metadata.get('inputs')!=fingerprint or metadata.get('log_sha256')!=sha(a.paper_log)
     or not metadata.get('complete') or metadata.get('process_exit')!=0
     or not {'HardcoreMP','HardcoreBosses'}<=set(metadata.get('required_plugins',[]))):
  sys.exit('Paper evidence does not match current sources, dependencies, required plugins or log')
 log=a.paper_log.read_text(errors='replace');names=re.findall(r'HM2_PAPER_CONTRACT PASS (\w+)',log);passed=set(names);end=re.findall(r'HM2_PAPER_CONTRACT END (\d+)',log)
 expected=set(re.findall(r'suites\.put\("(\w+)"',(root/'tools/PaperContractHarness.java').read_text()))
 if (len(end)!=1 or len(names)!=len(passed) or len(passed)!=int(end[0]) or passed!=expected
     or passed!=set(metadata.get('passed_suites',[])) or 'HM2_PAPER_CONTRACT FAIL' in log
     or 'HM2_PAPER_CONTRACT HARNESS_FAILURE' in log
     or any('HM2_PAPER_CONTRACT REQUIRED_PLUGIN_ENABLED '+x not in log for x in ('HardcoreMP','HardcoreBosses'))):sys.exit('Incomplete or failed Paper log')
 for row in results:
  if row.get('name','').rsplit('.',1)[-1] in passed and row['status'] in {'FAIL','NO_MAIN'}:row['status']='PASS_PAPER'
if fingerprint!=inputs(root,a.classpath,a.java_home):sys.exit('Inputs changed during verification')
(out/'verification-inputs.json').write_text(json.dumps(fingerprint,indent=2)+'\n')
(out/'results.json').write_text(json.dumps(results,indent=2),encoding='utf8')
print('Scope: JVM ONLY (not certification)' if a.jvm_only else 'Scope: FULL STRICT')
print({s:sum(r['status']==s for r in results) for s in sorted({r['status'] for r in results})},flush=True)
for row in results:
    if row['status'] in {'FAIL','TIMEOUT'}:
        show_failure(row['name'].rsplit('.',1)[-1], f"{row['status']} exit={row['exit_code']}")
    elif row['status'] in {'PENDING_PAPER','BLOCKED_FAKE_MISSING'}:
        print(f"[{row['status']}] {row['file']}",flush=True)
print(f'Results: {out / "results.json"}',flush=True)
if a.jvm_only:
 allowed={'PASS','PENDING_PAPER','BLOCKED_FAKE_MISSING'}
 sys.exit(0 if any(r['status']=='PASS' for r in results) and all(r['status'] in allowed for r in results) else 1)
sys.exit(0 if results and all(r['status'] in {'PASS','PASS_PAPER'} for r in results) else 1)
