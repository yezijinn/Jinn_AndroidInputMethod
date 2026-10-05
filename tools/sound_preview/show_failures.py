import re, os
d = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/build/test-results/testDebugUnitTest'
for name in os.listdir(d):
    if not name.endswith('.xml'):
        continue
    s = open(os.path.join(d, name), encoding='utf-8', errors='ignore').read()
    if '<failure' not in s and '<error' not in s:
        continue
    print('===', name)
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error)([^>]*)>', s):
        print('  TEST:', m.group(1))
        msg = m.group(2)
        mm = re.search(r'message="([^"]*)"', msg)
        print('  MSG :', (mm.group(1) if mm else msg)[:900])
        print()
