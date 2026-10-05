import os, re
d = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/build/test-results/testDebugUnitTest'
tot = 0; bad = 0; cls = 0
for n in os.listdir(d):
    if not n.endswith('.xml'):
        continue
    s = open(os.path.join(d, n), encoding='utf-8', errors='ignore').read()
    m = re.search(r'tests="(\d+)"', s)
    f = re.search(r'failures="(\d+)"', s)
    e = re.search(r'errors="(\d+)"', s)
    if m:
        t = int(m.group(1)); tot += t
        bad += int(f.group(1)) if f else 0
        bad += int(e.group(1)) if e else 0
        if t > 0:
            cls += 1
print('classes(含@Test)=', cls, ' tests=', tot, ' failures+errors=', bad)
