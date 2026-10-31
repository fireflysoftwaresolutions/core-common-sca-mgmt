"""Expose counts and bounded redacted errors, never raw Maven/provider logs."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
summary_file = ROOT / '.qualification/source-summary.json'
summary = json.loads(summary_file.read_text())
summary['maven_exit'] = int(sys.argv[1])
summary['reports'] = []
causes = []
for path in sorted(ROOT.glob('*/target/surefire-reports/TEST-*.xml')):
    report = ET.parse(path).getroot()
    summary['reports'].append({key: report.get(key) for key in ('name', 'tests', 'failures', 'errors', 'skipped')})
    for failure in report.findall('.//error') + report.findall('.//failure'):
        causes.extend(line.strip() for line in (failure.text or '').splitlines() if line.lstrip().startswith('Caused by:'))
summary_file.write_text(json.dumps(summary, indent=2) + '\n')
print('Source fixture Maven exit:', summary['maven_exit'])
print('JUnit report files:', len(summary['reports']))
errors = [line for line in (ROOT / '.qualification/maven.log').read_text(errors='replace').splitlines()
          if line.startswith('[ERROR]')]
for line in [*errors[-20:], *causes[-10:]]:
    line = re.sub(r'[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}', '[REDACTED JWT]', line)
    line = re.sub(r'(?i)((?:password|secret|token)\s*[=:]\s*)[^\s,;]+', r'\1[REDACTED]', line)
    line = re.sub(r'(https?://[^\s?]+)\?[^\s]+', r'\1?[REDACTED]', line)
    print(line[:700])
if summary['maven_exit'] == 0:
    database = [report for report in summary['reports'] if report['name'].endswith('.SCARecoveryRepositoryIT')]
    native_contract = [report for report in summary['reports'] if report['name'].endswith('.SCARecoveryNativeContractIT')]
    if len(native_contract) != 1 or int(native_contract[0]['tests']) < 6 or int(native_contract[0]['skipped']) != 0:
        raise SystemExit('Required owning transaction/native contract suite did not execute without skips')
    if len(database) != 1 or int(database[0]['tests']) < 13 or int(database[0]['skipped']) != 0:
        raise SystemExit('Required real PostgreSQL suite did not execute without skips')
