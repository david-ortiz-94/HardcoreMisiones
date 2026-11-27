#!/usr/bin/env python3
"""Fail closed while any development milestone criterion or current technical gate is open.
This is an acceptance check, not an implementation or a substitute for review.
"""
import argparse
import csv
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--architecture', required=True, type=Path)
parser.add_argument('--results', required=True, type=Path)
parser.add_argument('--paper-log', required=True, type=Path)
parser.add_argument('--doclint-exit', required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
issues = []

def require(condition, reason):
    if not condition:
        issues.append(reason)

try:
    milestone = (root / 'docs/HITO_ENTREGABLE_1.md').read_text(encoding='utf8')
    statuses = dict(re.findall(r'^\| (E1-[A-I]) \|[^\n]*?\| (ABIERTO|ACEPTADO) \|', milestone, re.M))
    require(set(statuses) == {f'E1-{c}' for c in 'ABCDEFGHI'}, 'Missing or invalid E1 acceptance criteria')
    for criterion, status in sorted(statuses.items()):
        require(status == 'ACEPTADO', f'{criterion}: {status}')
    with (root / 'docs/MATRIZ_TRAZABILIDAD.csv').open(encoding='utf8', newline='') as f:
        rows = list(csv.DictReader(f))
    expected = {f'R{i:02}' for i in range(1,41)} | {f'RG{i:02}' for i in range(1,10)}
    require(len(rows) == 49 and {r['id'] for r in rows} == expected, 'Incomplete or duplicate traceability matrix')
    architecture = json.loads(args.architecture.read_text())
    for key in ('class_cycles', 'package_cycles', 'reverse_root_dependencies', 'pure_direct_paper_dependencies'):
        require(key in architecture and not architecture[key], f'Architecture: {key} open or missing')
    results = json.loads(args.results.read_text())
    expected_tests = {str(x.relative_to(root)) for folder in ('src/test/java','legacy/recuperacion_rc3_original/tests')
                      for x in (root / folder).rglob('*Test.java')}
    actual_tests = [row['file'] for row in results]
    require(len(actual_tests) == len(set(actual_tests)) and set(actual_tests) == expected_tests,
            'Current full test inventory missing, duplicate or incomplete; targeted tests are insufficient')
    for row in results:
        require(row['status'] in {'PASS','PASS_PAPER'}, f"Test {row['file']}: {row['status']}")
    log = args.paper_log.read_text(errors='replace')
    for plugin in ('HardcoreMP','HardcoreBosses'):
        require(f'HM2_PAPER_CONTRACT REQUIRED_PLUGIN_ENABLED {plugin}' in log, f'{plugin} not confirmed enabled')
    passes = set(re.findall(r'HM2_PAPER_CONTRACT PASS (\w+)', log))
    end = re.findall(r'HM2_PAPER_CONTRACT END (\d+)', log)
    require(bool(end) and len(passes) == int(end[-1]) and len(passes) >= 17 and 'HM2_PAPER_CONTRACT FAIL' not in log and 'HM2_PAPER_CONTRACT HARNESS_FAILURE' not in log, 'Incomplete or failed Paper suites')
    require(args.doclint_exit.read_text().strip() == '0', 'Strict doclint not passed')
except (OSError, ValueError, KeyError, TypeError) as ex:
    issues.append(f'Missing or invalid evidence: {ex}')
print('E1 ABIERTO' if issues else 'E1 gates recorded as accepted (requires reviewed current-source evidence)')
for issue in issues:
    print('- ' + issue)
raise SystemExit(1 if issues else 0)
