#!/usr/bin/env python3
"""Static checks for the TikTok Grab sources, run in CI before Gradle is allowed to start.

Catches the classes of error that used to cost a whole CI round: unbalanced brackets in
Kotlin, XML that will not parse / carries a raw '&' or a \\u escape, an R.string or R.id
that points at nothing, a changed call shape that one call site did not follow, and a
Gradle wrapper that went missing.

    python3 tools/check_sources.py .
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding='utf-8')
    except (AttributeError, ValueError):
        pass

problems = []
infos = []


def problem(msg):
    problems.append(msg)


def skip(path: Path, root: Path) -> bool:
    parts = path.relative_to(root).parts
    return any(p in ('build', '.git', '.gradle') for p in parts)


# --- Kotlin -------------------------------------------------------------------------------

def strip_kotlin(src):
    """Blank out comments and string/char literals, keeping line count, so brackets can be counted."""
    out = []
    i, n = 0, len(src)
    state = None  # None | 'line' | 'block' | 'str' | 'raw' | 'char'
    depth = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = 'line'; out.append('  '); i += 2; continue
            if c == '/' and nxt == '*':
                state = 'block'; depth = 1; out.append('  '); i += 2; continue
            if c == '"':
                if src.startswith('"""', i):
                    state = 'raw'; out.append('   '); i += 3; continue
                state = 'str'; out.append(' '); i += 1; continue
            if c == "'":
                state = 'char'; out.append(' '); i += 1; continue
            out.append(c); i += 1; continue
        if state == 'line':
            if c == '\n':
                state = None; out.append('\n')
            else:
                out.append(' ')
            i += 1; continue
        if state == 'block':
            if c == '/' and nxt == '*':
                depth += 1; out.append('  '); i += 2; continue
            if c == '*' and nxt == '/':
                depth -= 1; out.append('  '); i += 2
                if depth == 0:
                    state = None
                continue
            out.append('\n' if c == '\n' else ' '); i += 1; continue
        if state == 'str':
            if c == '\\':
                out.append('  '); i += 2; continue
            if c == '"':
                state = None
            out.append(' '); i += 1; continue
        if state == 'raw':
            if src.startswith('"""', i):
                state = None; out.append('   '); i += 3; continue
            out.append('\n' if c == '\n' else ' '); i += 1; continue
        if state == 'char':
            if c == '\\':
                out.append('  '); i += 2; continue
            if c == "'":
                state = None
            out.append(' '); i += 1; continue
    if state in ('block', 'str', 'raw', 'char'):
        problem("unterminated " + state + " literal at end of source")
    return ''.join(out)


def check_kotlin(root):
    pairs = {'{': '}', '(': ')', '[': ']'}
    closers = {v: k for k, v in pairs.items()}
    for path in sorted(root.rglob('*.kt')):
        if skip(path, root):
            continue
        rel = path.relative_to(root)
        raw = path.read_text(encoding='utf-8')
        if '\ufffd' in raw:
            problem(str(rel) + ": replacement character in file (encoding damage)")
        clean = strip_kotlin(raw)
        line = 1
        stack = []
        broken = False
        for ch in clean:
            if ch == '\n':
                line += 1
            elif ch in pairs:
                stack.append((ch, line))
            elif ch in closers:
                if not stack:
                    problem(str(rel) + ":" + str(line) + ": unmatched '" + ch + "'")
                    broken = True
                    break
                opener, oline = stack.pop()
                if opener != closers[ch]:
                    problem(str(rel) + ":" + str(line) + ": '" + ch + "' closes '" + opener +
                            "' opened on line " + str(oline))
                    broken = True
                    break
        if not broken and stack:
            opener, oline = stack[-1]
            problem(str(rel) + ":" + str(oline) + ": '" + opener + "' never closed")


# --- XML ----------------------------------------------------------------------------------

def check_xml(root):
    res = root / 'app/src/main/res'
    if not res.is_dir():
        problem("app/src/main/res is missing")
        return
    for path in sorted(res.rglob('*.xml')):
        rel = path.relative_to(root)
        text = path.read_text(encoding='utf-8')
        try:
            ET.fromstring(text)
        except ET.ParseError as e:
            problem(str(rel) + ": XML does not parse: " + str(e))
        if re.search(r'\\u[0-9a-fA-F]{4}', text):
            problem(str(rel) + ": contains a \\u escape (AAPT rejects these in resources)")


def check_refs(root):
    src = root / 'app/src/main/java/com/rejwane/tiktokgrab'
    strings = set()
    ids = set()
    for p in (root / 'app/src/main/res').rglob('*.xml'):
        t = p.read_text(encoding='utf-8')
        if p.parent.name == 'values':
            strings |= set(re.findall(r'<string name="([A-Za-z0-9_]+)"', t))
        ids |= set(re.findall(r'@\+id/([A-Za-z0-9_]+)', t))

    used_strings, used_ids = {}, {}
    ours = re.compile(r'(?<!android\.)R\.(string|id)\.([A-Za-z0-9_]+)')
    for p in src.rglob('*.kt'):
        t = p.read_text(encoding='utf-8')
        for kind, name in ours.findall(t):
            if kind == 'string':
                used_strings.setdefault(name, set()).add(p.name)
            else:
                used_ids.setdefault(name, set()).add(p.name)

    for name, where in sorted(used_strings.items()):
        if name not in strings:
            problem("R.string." + name + " used in " + ', '.join(sorted(where)) +
                    " but not defined in values/*.xml")
    for name, where in sorted(used_ids.items()):
        if name not in ids:
            problem("R.id." + name + " used in " + ', '.join(sorted(where)) +
                    " but not defined in any layout")


def check_call_sites(root):
    """The shapes that changed in R2 must stay consistent everywhere."""
    src = root / 'app/src/main/java/com/rejwane/tiktokgrab'
    fetch_sites = 0
    for p in src.rglob('*.kt'):
        t = p.read_text(encoding='utf-8')
        for m in re.finditer(r'Comments\.fetch\([^)]*\)([^\n]*)', t):
            fetch_sites += 1
            if re.match(r'\.size\b', m.group(1).strip()):
                problem(p.name + ": Comments.fetch(...) still read as a list, but it returns Fetched")
        for m in re.finditer(r'pullComments\([^)]*\)\s*:\s*(\w+)', t):
            if m.group(1) != 'Boolean':
                problem(p.name + ": pullComments returns " + m.group(1) +
                        ", but CommentSheet.show expects Boolean")
    infos.append("Comments.fetch call sites: " + str(fetch_sites))


def check_wrapper(root):
    needed = ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
              'gradle/wrapper/gradle-wrapper.properties']
    for name in needed:
        if not (root / name).is_file():
            problem("Gradle wrapper: missing " + name)


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
    check_kotlin(root)
    check_xml(root)
    check_refs(root)
    check_call_sites(root)
    check_wrapper(root)
    if problems:
        print("FAIL - " + str(len(problems)) + " problem(s):")
        for p in problems:
            print("  - " + p)
        return 1
    print("OK - Kotlin brackets balanced, XML parses, every R.string / R.id resolves, wrapper present.")
    for line in infos:
        print("  . " + line)
    return 0


if __name__ == '__main__':
    sys.exit(main())
