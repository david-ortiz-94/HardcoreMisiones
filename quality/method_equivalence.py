#!/usr/bin/env python3
"""Match original methods structurally; changed or identical bodies still require semantic review."""
import argparse, collections, csv, json, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java-home',required=True,type=Path);p.add_argument('--baseline',required=True,type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
def scan(label,path):
 f=a.output/(label+'-methods.csv')
 with f.open('wb') as out,(a.output/(label+'-methods.log')).open('wb') as err:
  subprocess.run([str(a.java_home/'bin/java'),str(root/'quality/MethodEquivalence.java'),str(path)],stdout=out,stderr=err,check=True)
 with f.open(encoding='utf8') as h:return list(csv.DictReader(h))
old=scan('hf5',a.baseline);new=scan('current',root/'src/main/java')
with (root/'docs/INVENTARIO_CLASES.csv').open(encoding='utf8') as f:
 mappings={x['ruta_HF5'].removeprefix('src/main/java/'):x['ruta_actual'].removeprefix('src/main/java/') for x in csv.DictReader(f) if x['ruta_HF5']}
byfile=collections.defaultdict(list);byhash=collections.defaultdict(list)
for x in new:
 byfile[x['file']].append(x)
 if x['body_sha256']:byhash[x['body_sha256']].append(x)
def ref(x):return f"{x['file']}:{x['line']} {x['class']}.{x['signature']}"
result=[]
for x in old:
 target=mappings.get(x['file'],x['file']);candidates=byfile[target]
 same=[r for r in candidates if r['signature']==x['signature']]
 body=[r for r in same if r['body_sha256']==x['body_sha256'] and r['kind']==x['kind']]
 anywhere=byhash[x['body_sha256']] if x['body_sha256'] else []
 local_body=[r for r in anywhere if r['file']==target]
 if len(body)==1:status='FIRMA_Y_CUERPO_IDENTICOS' if x['body_sha256'] else 'DECLARACION_MISMA_FIRMA';found=body
 elif len(body)>1:status='AMBIGUO';found=body
 elif len(same)==1:status='FIRMA_IGUAL_CUERPO_CAMBIADO';found=same
 elif len(local_body)==1:status='CUERPO_IDENTICO_EN_CLASE_MAPEADA';found=local_body
 elif len(anywhere)==1:status='CUERPO_IDENTICO_EN_OTRA_UBICACION';found=anywhere
 elif same or anywhere:status='AMBIGUO';found=(same or anywhere)[:10]
 else:status='SIN_CORRESPONDENCIA_ESTRUCTURAL';found=[]
 result.append({'origen':ref(x),'sha256_cuerpo_HF5':x['body_sha256'],'clasificacion':status,'candidatos':'; '.join(map(ref,found)),'revision_semantica':'PENDIENTE / correspondencia sintáctica no certifica comportamiento'})
with (a.output/'correspondencia-metodos.csv').open('w',encoding='utf8',newline='') as f:
 w=csv.DictWriter(f,fieldnames=result[0]);w.writeheader();w.writerows(result)
report={'metodos_y_declaraciones_HF5':len(old),'metodos_y_declaraciones_actuales':len(new),'clasificacion':dict(collections.Counter(r['clasificacion'] for r in result)),'limite':'Comparación del AST normalizado y firma textual; no analiza bindings, flujo, efectos, llamadas ni equivalencia semántica. No cambia el gate E1-B.'}
(a.output/'correspondencia-resumen.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
