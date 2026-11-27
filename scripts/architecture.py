#!/usr/bin/env python3
"""Inspect internal class/package dependencies from freshly compiled Java 25 bytecode.
Package boundaries are a gate, class SCCs are reported separately (not waived).
"""
import argparse, collections, json, re, subprocess, sys
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--java-home',type=Path,required=True);p.add_argument('--classes',type=Path,required=True);p.add_argument('--classpath',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root='com.hardcoremisiones'
proc=subprocess.run([str(a.java_home/'bin/jdeps'),'--multi-release','25','--ignore-missing-deps','-verbose:class','-filter:none','--class-path',a.classpath,str(a.classes)],capture_output=True,text=True,check=True)
classes={str(f.relative_to(a.classes)).removesuffix('.class').replace('/','.').split('$')[0] for f in a.classes.rglob('*.class')}
graph={x:set() for x in classes}; packages={x.rpartition('.')[0] for x in classes}; pg={x:set() for x in packages}; external=collections.defaultdict(set)
for line in proc.stdout.splitlines():
 m=re.match(r'\s+(\S+)\s+->\s+(\S+)\s+',line)
 if not m:continue
 s,t=(x.split('$')[0] for x in m.groups())
 if s not in graph:continue
 if t not in graph:external[s].add(t);continue
 if s==t:continue
 graph[s].add(t);sp,tp=s.rpartition('.')[0],t.rpartition('.')[0]
 if sp!=tp:pg[sp].add(tp)
def cycles(g):
 # A node's strongly connected component is its forward/reverse reachability intersection.
 rev={n:set() for n in g}
 for n,ts in g.items():
  for t in ts:rev[t].add(n)
 def visit(x,g):
  seen=set();todo=[x]
  while todo:
   n=todo.pop()
   if n in seen:continue
   seen.add(n);todo.extend(g[n]-seen)
  return seen
 remain=set(g);result=[]
 while remain:
  x=min(remain);c=visit(x,g)&visit(x,rev);remain-=c
  if len(c)>1:result.append(sorted(c))
 return result
roots={x for x in classes if x in {root+'.HardcoreMisiones',root+'.f3.api.BossProvider'}}
seen=set();todo=list(roots)
while todo:
 n=todo.pop()
 if n in seen:continue
 seen.add(n);todo.extend(graph[n]-seen)
reverse=sorted([s,t] for s,ts in graph.items() for t in ts if s.rpartition('.')[0]!=root and t.rpartition('.')[0]==root)
pure=[root+'.rewards.encounters.EncounterEconomy',root+'.missions.OfferingTransaction']
pure_violations=sorted([s,t] for s in pure for t in external[s] if t.startswith(('org.bukkit.','io.papermc.')))
report={'classes':len(classes),'root_classes':sum(x.rpartition('.')[0]==root for x in classes),'packages':{k:sorted(v) for k,v in sorted(pg.items())},'package_cycles':cycles(pg),'class_cycles':cycles(graph),'reverse_root_dependencies':reverse,'pure_direct_paper_dependencies':pure_violations,'unreachable_from_plugin_or_spi_static_only':sorted(classes-seen),'limitations':'Static bytecode only; reflection and external callers not proven. Root class cycles are not a pass.'}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n');print('classes:',len(classes),'root:',report['root_classes'],'package cycles:',len(report['package_cycles']),'class cycles:',len(report['class_cycles']),'reverse root:',len(reverse),'pure violations:',len(pure_violations),'unreachable:',len(classes-seen));sys.exit(1 if report['package_cycles'] or reverse or pure_violations or report['class_cycles'] else 0)
