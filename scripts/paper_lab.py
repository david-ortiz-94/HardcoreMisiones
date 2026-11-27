#!/usr/bin/env python3
"""Build private instrumented test jars and run Paper in an EMPTY disposable directory.
No artifact produced by this script is a distribution JAR. Never target a real server.
Requires Java 25, Paper build 135, exact transitive API classpath and offline Mojang cache.
"""
import argparse, pathlib, subprocess, zipfile, shutil, os, re, sys, json
from evidence_inputs import inputs, sha
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--java-home',required=True,type=pathlib.Path)
p.add_argument('--classpath',required=True)
p.add_argument('--paper',required=True,type=pathlib.Path)
p.add_argument('--mojang-cache',required=True,type=pathlib.Path)
p.add_argument('--lab',required=True,type=pathlib.Path)
p.add_argument('--plugin',action='append',default=[],type=pathlib.Path,help='Optional original provider jar(s); use separate world lab for HardcoreMP')
p.add_argument('--level-name',default='hcmp_game',choices=['world','hcmp_game'])
p.add_argument('--require-plugin',action='append',default=[],choices=['HardcoreMP','HardcoreBosses'])
a=p.parse_args(); root=pathlib.Path(__file__).resolve().parents[1]; lab=a.lab.resolve()
if lab.exists() and any(lab.iterdir()):sys.exit('Refusing nonempty lab directory')
fingerprint=inputs(root,a.classpath,a.java_home)
lab.mkdir(parents=True,exist_ok=True); (lab/'plugins').mkdir();(lab/'cache').mkdir()
shutil.copy2(a.mojang_cache,lab/'cache'/a.mojang_cache.name)
for f in a.plugin:shutil.copy2(f,lab/'plugins'/f.name)
(lab/'eula.txt').write_text('eula=true\n')
(lab/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port=25991\nonline-mode=false\nlevel-name={a.level_name}\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
javac=a.java_home/'bin/javac';java=a.java_home/'bin/java';out=lab/'compile';out.mkdir();main=out/'main';tests=out/'test';driver=out/'driver';main.mkdir();tests.mkdir();driver.mkdir()
def compile(files,dest,cp):
 arg=dest/'sources.args';arg.write_text('\n'.join('"'+str(f)+'"' for f in files));subprocess.run([str(javac),'--release','25','-encoding','UTF-8','-cp',cp,'-d',str(dest),'@'+str(arg)],check=True)
compile(sorted((root/'src/main/java').rglob('*.java')),main,a.classpath)
files=[f for f in sorted((root/'src/test/java').rglob('*.java')) if 'com.hardcorebosses.fake' not in f.read_text()]
compile(files+[root/'tools/PaperContractHarness.java'],tests,str(main)+os.pathsep+a.classpath)
compile([root/'tools/PaperLabLauncher.java'],driver,a.classpath)
with zipfile.ZipFile(lab/'plugins/HardcoreMisiones-INSTRUMENTED-NOT-RELEASE.jar','w',zipfile.ZIP_DEFLATED) as z:
 for folder in [main,tests]:
  for f in folder.rglob('*.class'):z.write(f,f.relative_to(folder))
 for f in (root/'src/main/resources').glob('*'):z.write(f,f.name)
with zipfile.ZipFile(lab/'plugins/HM2ContractLab.jar','w',zipfile.ZIP_DEFLATED) as z:
 for f in driver.rglob('*.class'):z.write(f,f.relative_to(driver))
 z.writestr('plugin.yml','name: HM2ContractLab\nversion: 1\nmain: hm2.lab.PaperLabLauncher\napi-version: "26.2"\ndepend: [HardcoreMisiones]\n')
with (lab/'paper-contracts.log').open('wb') as log:
 result=subprocess.run([str(java),'-Xms512m','-Xmx2g','-XX:ActiveProcessorCount=4','-Dhm2.rc8d.paperlab=true','-Dhm2.lab.requirePlugins='+','.join(a.require_plugin),'-jar',str(a.paper.resolve()),'nogui'],cwd=lab,stdout=log,stderr=subprocess.STDOUT,timeout=180)
s=(lab/'paper-contracts.log').read_text(errors='replace'); passed=re.findall(r'HM2_PAPER_CONTRACT PASS (\w+)',s); end=re.findall(r'HM2_PAPER_CONTRACT END (\d+)',s)
expected=set(re.findall(r'suites\.put\("(\w+)"', (root/'tools/PaperContractHarness.java').read_text()))
complete=bool(result.returncode==0 and end and len(end)==1 and len(passed)==len(set(passed))==int(end[-1]) and set(passed)==expected and 'HM2_PAPER_CONTRACT FAIL' not in s and 'HM2_PAPER_CONTRACT HARNESS_FAILURE' not in s and all('HM2_PAPER_CONTRACT REQUIRED_PLUGIN_ENABLED '+name in s for name in a.require_plugin))
unchanged=fingerprint==inputs(root,a.classpath,a.java_home)
metadata={'inputs':fingerprint,'paper':{'name':a.paper.name,'sha256':sha(a.paper)},'mojang_cache':{'name':a.mojang_cache.name,'sha256':sha(a.mojang_cache)},'plugins':[{'name':p.name,'sha256':sha(p)} for p in a.plugin],'required_plugins':a.require_plugin,'expected_suites':sorted(expected),'passed_suites':sorted(set(passed)),'log_sha256':sha(lab/'paper-contracts.log'),'complete':complete and unchanged,'process_exit':result.returncode}
(lab/'paper-inputs.json').write_text(json.dumps(metadata,indent=2)+'\n')
print('Paper suite passes:',len(passed),'end:',end,'exit:',result.returncode,'inputs unchanged:',unchanged)
sys.exit(0 if complete and unchanged else 1)
