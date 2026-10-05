import io
import os
import re

for name in ('app/build/reports/lint-results-debug.xml', 'app/build/reports/lint-results-debug.html'):
    if not os.path.isfile(name):
        continue
    s = io.open(name, encoding='utf-8', errors='ignore').read()
    errs = re.findall(r'severity="Error"', s)
    warns = re.findall(r'severity="Warning"', s)
    print(name, 'errors=', len(errs), 'warnings=', len(warns))
    if name.endswith('.xml'):
        for m in re.finditer(r'id="([^"]+)"[^>]*severity="(Error|Warning)"', s):
            print('   ', m.group(2), m.group(1))
    break
else:
    print('没有 lint 报告：', os.listdir('app/build/reports') if os.path.isdir('app/build/reports') else 'no dir')
