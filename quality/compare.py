#!/usr/bin/env python3
"""Compare source AST metrics using the same compiler and counting rules on both versions."""
import argparse, collections, csv, json, statistics, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java-home',required=True,type=Path);p.add_argument('--baseline',required=True,type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
def measure(label,src):
 f=a.output/(label+'-quality.csv')
 with f.open('wb') as out, (a.output/(label+'-quality.log')).open('wb') as err:
  subprocess.run([str(a.java_home/'bin/java'),str(root/'quality/SourceQuality.java'),str(src)],stdout=out,stderr=err,check=True)
 with f.open() as handle:rows=list(csv.DictReader(handle))
 scores=[int(r['decision_score']) for r in rows]; clones=collections.defaultdict(list)
 for r in rows:
  if r['exact_body_sha256']:clones[r['exact_body_sha256']].append({k:r[k] for k in ('file','class','method','start','end')})
 groups=[v for v in clones.values() if len(v)>1]
 return {'source_files':len(list(src.rglob('*.java'))),'concrete_methods_and_constructors':len(rows),'decision_mean':round(statistics.mean(scores),3),'decision_median':statistics.median(scores),'decision_max':max(scores),'decision_over_20':sum(s>20 for s in scores),'decision_over_50':sum(s>50 for s in scores),'exact_duplicate_groups_min_8_statement_nodes':len(groups),'exact_duplicate_members':sum(map(len,groups)),'duplicate_candidates':groups,'top_20':sorted(rows,key=lambda r:int(r['decision_score']),reverse=True)[:20]}
report={'methodology':'1 + if, loops, catches, ternary, logical &&/||, non-default case. Includes lambdas in enclosing method, excludes local/anonymous class internals (measured separately). Exact compiler-rendered method body SHA, minimum eight statement nodes; does not detect renamed or partial clones. No coverage, runtime performance or dead-code inference.','HF5':measure('hf5',a.baseline),'2.1.0':measure('current',root/'src/main/java')}
(a.output/'quality-comparison.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({label:{k:v for k,v in report[label].items() if k not in ('duplicate_candidates','top_20')} for label in ('HF5','2.1.0')},indent=2))
