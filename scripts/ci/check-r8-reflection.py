#!/usr/bin/env python3
"""Fails if R8 removed something that Android or a library instantiates reflectively.

Classes named in the merged manifest — components, androidx.startup initializers and
Firebase/ML Kit `com.google.firebase.components:<Registrar>` meta-data — are created with
`newInstance()` at runtime. R8 full mode does not keep their no-arg constructors for plain
`-keep class` rules, which compiles fine and then crashes on device (e.g. ML Kit translation
NPE in v0.1.0). This reads R8's mapping/usage reports and the merged manifest to catch that.

Usage: check-r8-reflection.py [variant]   (default: release; run after assemble<Variant>)
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

variant = sys.argv[1] if len(sys.argv) > 1 else "release"
base = "app/build"
mapping_dir = f"{base}/outputs/mapping/{variant}"
ANDROID = "{http://schemas.android.com/apk/res/android}"

manifests = glob.glob(f"{base}/intermediates/merged_manifest/{variant}/**/AndroidManifest.xml", recursive=True)
if not manifests:
    sys.exit(f"merged manifest for '{variant}' not found — run ./gradlew assemble{variant.capitalize()} first")

try:
    mapping = open(f"{mapping_dir}/mapping.txt", encoding="utf-8").read()
    usage = open(f"{mapping_dir}/usage.txt", encoding="utf-8").read()
except FileNotFoundError as e:
    sys.exit(f"R8 report missing ({e.filename}); is minification enabled for '{variant}'?")

kept_classes = set(re.findall(r"^([\w.$]+) -> [\w.$]+:$", mapping, re.M))
fqcn = re.compile(r"^[a-zA-Z_][\w$]*(\.[a-zA-Z_][\w$]*)+$")

candidates = set()
root = ET.parse(manifests[0]).getroot()
package = root.get("package", "")
app = root.find("application")
for el in root.iter():
    name = el.get(ANDROID + "name") or ""
    if el.tag in ("activity", "service", "receiver", "provider", "application") and name:
        candidates.add(package + name if name.startswith(".") else name)
    elif el.tag == "meta-data":
        value = el.get(ANDROID + "value") or ""
        if name.startswith("com.google.firebase.components:"):
            candidates.add(name.split(":", 1)[1])
        elif value == "androidx.startup":
            candidates.add(name)

# usage.txt lists removed classes as "cls" and removed members under "cls:".
removed_classes = set(re.findall(r"^([\w.$]+)$", usage, re.M))
removed_ctors = set()
current = None
for line in usage.splitlines():
    if line and not line.startswith(" "):
        current = line[:-1] if line.endswith(":") else None
    elif current and re.match(r"^\s+(public |protected |private )?void <init>\(\)$", line):
        removed_ctors.add(current)

problems = []
for cls in sorted(c for c in candidates if fqcn.match(c)):
    if cls in removed_classes:
        problems.append(f"{cls}: class removed by R8")
    elif cls in removed_ctors:
        problems.append(f"{cls}: no-arg constructor removed by R8 (instantiated reflectively)")
    elif cls not in kept_classes and cls in mapping:
        pass  # present under a different form; not a reflection target we can verify

if problems:
    print("::error::R8 stripped classes/constructors that are created reflectively at runtime:")
    for p in problems:
        print(f"  - {p}")
    print("Add keep rules to app/proguard-rules.pro, e.g. -keep class <cls> { <init>(); }")
    sys.exit(1)
print(f"R8 reflection check OK: {len(candidates)} manifest-referenced classes keep their constructors.")
