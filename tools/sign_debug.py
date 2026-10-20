"""Sign the debug APK with the project's official keystore (JinnKeyStores).

Reuses exactly the same keystore / alias / password source as build_apk.py:
  <JINN_KEYSTORE_ROOT>/<applicationId>/release.jks
  <JINN_KEYSTORE_ROOT>/JinnPassword.md   (`-storepass` / `-keypass` table rows)
Alias is the applicationId. ASCII-only on purpose (no console encoding issues).
"""
import glob
import io
import os
import re
import subprocess

ROOT = r"C:\AI_WORKSPACE\PROJECTS\com.jinn.inputmethod"
PROJ = "com.jinn.inputmethod"
ks_root = os.environ["JINN_KEYSTORE_ROOT"]

keystore = os.path.join(ks_root, PROJ, "release.jks")
pw_file = os.path.join(ks_root, "JinnPassword.md")
if not os.path.isfile(keystore):
    raise SystemExit("keystore not found: " + keystore)
if not os.path.isfile(pw_file):
    raise SystemExit("password file not found: " + pw_file)

text = io.open(pw_file, encoding="utf-8").read()


def opt(name):
    for line in text.splitlines():
        if re.search(r"`-%s`" % name, line, re.IGNORECASE):
            cells = [c.strip().strip("`") for c in line.split("|")]
            vals = [c for c in cells[1:] if c]
            if vals:
                return vals[-1]
    raise SystemExit("missing -" + name + " in " + pw_file)


store_pass = opt("storepass")
key_pass = opt("keypass")

bt_dirs = sorted(glob.glob(r"C:\Android\sdk\build-tools\*"))
if not bt_dirs:
    raise SystemExit("no build-tools found")
bt = bt_dirs[-1]
zipalign = os.path.join(bt, "zipalign.exe")
apksigner = os.path.join(bt, "apksigner.bat")

src = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
aligned = os.path.join(ROOT, "debug-aligned.apk")
out = os.path.join(ROOT, "debug-signed.apk")

subprocess.check_call([zipalign, "-p", "-f", "4", src, aligned])
subprocess.check_call([
    apksigner, "sign",
    "--ks", keystore,
    "--ks-key-alias", PROJ,
    "--ks-pass", "pass:" + store_pass,
    "--key-pass", "pass:" + key_pass,
    "--v2-signing-enabled", "true",
    "--v3-signing-enabled", "true",
    "--out", out,
    aligned,
])
subprocess.check_call([apksigner, "verify", "--print-certs", out])
print("SIGNED", out, os.path.getsize(out))
