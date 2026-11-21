import re
import os
import io

ROOT = "app/src/main/kotlin/io/github/proify/astrafirmament"
TARGETS = ["LocalLyric", "LocalRecipe", "LocalLyricFinder", "LyricParsers",
           "KuwoLyric", "LunaLyric", "QrcDecrypt"]

class_pkg = {}
for dirpath, _, fns in os.walk(ROOT):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dirpath, fn)
        src = io.open(p, encoding="utf-8").read()
        m = re.search(r"^package\s+(\S+)", src, re.M)
        if not m:
            continue
        pkg = m.group(1)
        for cm in re.finditer(r"^(?:internal\s+|public\s+)*(?:object|class|data\s+class)\s+(\w+)", src, re.M):
            class_pkg.setdefault(cm.group(1), pkg)

report = []
for dirpath, _, fns in os.walk(os.path.join(ROOT, "hook")):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dirpath, fn)
        src = io.open(p, encoding="utf-8").read()
        m = re.search(r"^package\s+(\S+)", src, re.M)
        if not m:
            continue
        pkg = m.group(1)
        code = re.sub(r"/\*[\s\S]*?\*/", " ", src)
        code = re.sub(r"^import .*$", "", code, flags=re.M)
        need = []
        for t in TARGETS:
            tp = class_pkg.get(t)
            if not tp or tp == pkg:
                continue
            if not re.search(r"(?<![.\w])" + t + r"(?![\w])", code):
                continue
            if re.search(r"^import\s+" + re.escape(tp) + r"\." + t + r"\s*$", src, re.M):
                continue
            need.append("import " + tp + "." + t)
        if need:
            lines = src.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            for imp in need:
                lines.insert(last + 1, imp)
                last += 1
            io.open(p, "w", encoding="utf-8").write("\n".join(lines))
            report.append((os.path.relpath(p, ROOT), need))

for f, n in report:
    print(f, "->", n)
print("共补", sum(len(n) for _, n in report), "处")
