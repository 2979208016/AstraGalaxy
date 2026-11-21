#!/usr/bin/env python3
"""引用一致性静态门（本地编译不可用时的替代检查）。

三个检查，覆盖"改包名/拆文件"最容易漏的三类错误：
  1. 跨包符号引用是否 import —— 剔除注释后匹配，避免 KDoc 里提类名造成的误报；
  2. 行内全限定引用 io.github.proify.astrafirmament.<pkg>.<Name> 的包是否与
     Name 的真实声明包一致 —— CI 反复报 Unresolved reference 的根因就在这里；
  3. 同包重复顶层声明 —— 拆文件后最易出的 redeclaration。

用法：python3 docs/check_refs.py
退出码 0 = 通过。
"""
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "app", "src", "main", "kotlin",
                    "io", "github", "proify", "astrafirmament")
ROOT = os.path.normpath(ROOT)

# aapt 生成的 R 不是 .kt 声明，跳过
SKIP_IMPORTS = {"io.github.proify.astrafirmament.R"}

DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|internal|private|open|abstract|sealed|data|enum|annotation|"
    r"inline|value|const|suspend|actual|expect|operator|infix|tailrec|external)\s+)*"
    r"(class|object|interface|typealias|fun|val|var)\s+"
    r"(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)")
FQ_RE = re.compile(r"io\.github\.proify\.astrafirmament((?:\.[a-z0-9_]+)*)\.([A-Z]\w*)")
STRING_RE = re.compile(r'"(?:[^"\\]|\\.)*"')


def load():
    files = []
    for dirpath, _, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".kt"):
                files.append(os.path.join(dirpath, n))
    return sorted(files)


def split(src):
    """返回 (package, imports[(fq, alias)], 顶层声明名集合, 去掉注释与 import 的正文)"""
    pkg = None
    imports = []
    body_lines = []
    in_block = False
    for line in src.splitlines():
        st = line.strip()
        if in_block:
            if "*/" in st:
                in_block = False
            continue
        if st.startswith("/*"):
            if "*/" not in st:
                in_block = True
            continue
        m = re.match(r"^package\s+([\w.]+)", line)
        if m and pkg is None:
            pkg = m.group(1)
            continue
        m = re.match(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", line)
        if m:
            imports.append((m.group(1), m.group(2)))
            continue
        if st.startswith("//") or st.startswith("*"):
            continue
        # 剔除字符串字面量：广播 action / 日志文案里出现的类名不是引用
        body_lines.append(STRING_RE.sub('""', re.sub(r"//.*$", "", line)))
    decls = set()
    for line in body_lines:
        if not line or line[0] in " \t}":
            continue
        m = DECL_RE.match(line)
        if m and m.group(2) not in ("by", "get", "set"):
            # private 顶层声明在别的文件里既不能 import 也不能引用，
            # 不参与「跨包引用是否 import」的判定（否则 tint/view/scheme 这类短名会假阳性）。
            head = re.sub(r"^@\w+(?:\([^)]*\))?\s*", "", line)
            if not re.match(r"^(?:private)\b", head):
                decls.add(m.group(2))
    return pkg, imports, decls, "\n".join(body_lines), body_lines


def main():
    files = load()
    if not files:
        print("找不到源码：%s" % ROOT)
        return 1

    info = {}
    declared = defaultdict(dict)   # name -> {pkg: path}
    for p in files:
        pkg, imports, decls, body, body_lines = split(open(p, encoding="utf-8").read())
        info[p] = dict(pkg=pkg, imports=imports, decls=decls, body=body, lines=body_lines)
        for d in decls:
            declared[d].setdefault(pkg, p)

    problems = []

    # 1. 跨包符号引用是否 import
    for p, d in info.items():
        names = set()
        for fq, alias in d["imports"]:
            if fq in SKIP_IMPORTS:
                continue
            if fq.endswith(".*"):
                names.add(fq[:-2].rsplit(".", 1)[-1])
            else:
                names.add(alias or fq.rsplit(".", 1)[-1])
        for name, pkgs in declared.items():
            if name in names:
                continue
            owners = [q for q in pkgs if q != d["pkg"]]
            if not owners:
                continue
            if not re.search(r"\b%s\b" % re.escape(name), d["body"]):
                continue
            if any(("%s.%s" % (q, name)) in d["body"] for q in owners):
                continue
            problems.append("%s: 引用 %s（声明于 %s）但未 import"
                            % (os.path.relpath(p, ROOT), name, ", ".join(owners)))

    # 2. 行内全限定引用的包是否正确
    by_pkg = defaultdict(set)
    for p, d in info.items():
        by_pkg[d["pkg"]] |= d["decls"]
    for p, d in info.items():
        for i, line in enumerate(d["lines"], 1):
            for m in FQ_RE.finditer(line):
                pkg = "io.github.proify.astrafirmament" + m.group(1)
                name = m.group(2)
                if name in by_pkg.get(pkg, ()):
                    continue
                actual = [q for q, ns in by_pkg.items() if name in ns]
                if not actual:
                    continue  # 广播 action 之类的字符串常量，不是类型引用
                problems.append("%s:%d: 全限定引用 %s.%s 的包错了，实际声明于 %s"
                                % (os.path.relpath(p, ROOT), i, pkg, name, ", ".join(actual)))

    # 3. 项目内 import 是否指向真实存在的顶层声明（悬空导入）
    by_pkg = defaultdict(set)
    for p, d in info.items():
        by_pkg[d["pkg"]] |= d["decls"]
    for p, d in sorted(info.items()):
        for fq, _alias in d["imports"]:
            if fq in SKIP_IMPORTS or not fq.startswith("io.github.proify.astrafirmament."):
                continue
            if fq.endswith(".*"):
                if fq[:-2] not in by_pkg:
                    problems.append("%s: 通配 import 指向不存在的包 %s"
                                    % (os.path.relpath(p, ROOT), fq[:-2]))
                continue
            pkg, name = fq.rsplit(".", 1)
            if name in by_pkg.get(pkg, ()):
                continue
            actual = [q for q, ns in by_pkg.items() if name in ns]
            problems.append("%s: import %s 悬空（%s）"
                            % (os.path.relpath(p, ROOT), fq,
                               ("真实声明于 " + ", ".join(actual)) if actual else "该符号不存在"))

    # 4. 同包重复顶层声明
    seen = defaultdict(list)
    for p, d in info.items():
        for n in d["decls"]:
            seen[(d["pkg"], n)].append(os.path.relpath(p, ROOT))
    for (pkg, n), paths in sorted(seen.items()):
        if len(paths) > 1:
            problems.append("同包重复声明 %s.%s: %s" % (pkg, n, ", ".join(paths)))

    if problems:
        for x in problems:
            print("FAIL " + x)
        print("\n%d 处问题" % len(problems))
        return 1
    print("引用一致性检查通过：%d 文件，0 问题" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
