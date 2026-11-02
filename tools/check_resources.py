"""Every resource name the app points at, checked against the files that define it.

The build only happens in CI, and one @dimen or @anim that was never written costs a whole round
trip. This walks res/ and the Kotlin once and says so here instead.

    python tools/check_resources.py
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main")
RES = os.path.join(ROOT, "res")
KOTLIN = os.path.join(ROOT, "java")

# Folders that define a resource by file name, and the value kinds defined inside values/*.xml.
BY_FILE = ("drawable", "layout", "anim", "interpolator", "color", "mipmap", "xml")
BY_NAME = ("dimen", "color", "integer", "bool", "string", "array", "plurals", "style")

defined = {}
for kind in set(BY_FILE) | set(BY_NAME):
    defined.setdefault(kind, set())

ids = set()

for entry in os.listdir(RES):
    folder = os.path.join(RES, entry)
    if not os.path.isdir(folder):
        continue
    base = re.split(r'[_-]', entry)[0]  # values-night, mipmap-anydpi-v26, drawable-hdpi
    if base == "values":
        for name in os.listdir(folder):
            if not name.endswith(".xml"):
                continue
            text = open(os.path.join(folder, name), encoding="utf-8").read()
            for kind in BY_NAME:
                for ref in re.findall(r'<%s\s+name="([^"]+)"' % kind, text):
                    defined[kind].add(ref)
            # <item name="x" type="dimen"> also defines one.
            for kind, ref in re.findall(r'<item\s+name="([^"]+)"\s+type="([a-z]+)"', text):
                defined.setdefault(kind, set()).add(ref)
        continue
    if base in BY_FILE:
        for name in os.listdir(folder):
            if name.endswith(".xml") or "." in name:
                defined[base].add(name.split(".")[0])
        if base == "layout":
            for name in os.listdir(folder):
                text = open(os.path.join(folder, name), encoding="utf-8").read()
                ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))
                defined["layout"].add(name.split(".")[0])

missing = []


def check(where, kind, ref):
    if kind == "id":
        if ref not in ids:
            missing.append((where, "id/" + ref))
    elif kind == "android" or kind == "":
        return
    elif kind in defined:
        if ref not in defined[kind]:
            missing.append((where, kind + "/" + ref))
    # anything else (@null, @color inside android: attrs) is not ours to check


# 1. every @kind/name used in res/
for entry in os.listdir(RES):
    folder = os.path.join(RES, entry)
    if not os.path.isdir(folder):
        continue
    for name in os.listdir(folder):
        if not name.endswith(".xml"):
            continue
        text = open(os.path.join(folder, name), encoding="utf-8").read()
        # Drop android: references first so the regex only sees ours.
        text = re.sub(r'@android:[A-Za-z0-9_/.]+', ' ', text)
        text = re.sub(r'\?android:[A-Za-z0-9_/.]+', ' ', text)
        text = re.sub(r'\?[A-Za-z0-9_.]+', ' ', text)  # theme attributes
        for kind, ref in re.findall(r'@([a-z]+)/([A-Za-z0-9_.]+)', text):
            check(name, kind, ref)

# 2. every R.kind.name in Kotlin
for name in sorted(os.listdir(KOTLIN)):
    if not name.endswith(".kt"):
        continue
    text = open(os.path.join(KOTLIN, name), encoding="utf-8").read()
    for kind, ref in re.findall(r'\bR\.(id|dimen|color|integer|bool|array|string|plurals|anim|'
                                r'interpolator|layout|drawable|mipmap|style|xml)\.([A-Za-z0-9_]+)', text):
        check(name, kind, ref)

if missing:
    print("MISSING RESOURCES:")
    for where, ref in sorted(set(missing)):
        print("  %s -> @%s" % (where, ref))
    sys.exit(1)

print("OK - every @res and R.res reference resolves (%d names, %d ids)" %
      (sum(len(v) for v in defined.values()), len(ids)))
