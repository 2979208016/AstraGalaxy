"""星河静态检查（detekt 替代——本工程禁构建测试，用脚本实现核心规则）。

用法：python3 docs/checks.py
规则（对应规范第 6 条）：
  1. 非 Composable 函数 ≤ 60 行（超限报告）
  2. 壳文件（object 空+跨文件调用）
  3. 括号平衡（含三引号字符串剥离）
  4. 未用 import
  5. hook/ 包不引用 app 进程专属符号（架构边界，规划中的 hook/ 分包生效后启用）
"""
import re, os, sys

ROOT = "app/src/main/kotlin"

def strip_all(s):
    out = []; i = 0; n = len(s)
    while i < n:
        if s[i:i+3] == chr(34)*3:
            j = i + 3; closed = False
            while j < n:
                if s[j] == chr(34):
                    k = j
                    while k < n and s[k] == chr(34): k += 1
                    if k - j >= 3:
                        out.append(' '); i = k; closed = True; break
                    j = k
                else: j += 1
            if not closed: i = n; out.append(' ')
        elif s[i] == chr(34):
            j = i + 1
            while j < n:
                if s[j] == chr(92): j += 2; continue
                if s[j] == chr(34): break
                j += 1
            i = j + 1; out.append(' ')
        elif s[i] == chr(39):
            j = i + 1
            while j < n:
                if s[j] == chr(92): j += 2; continue
                if s[j] == chr(39): break
                j += 1
            i = j + 1; out.append(' ')
        elif s[i:i+2] == '//':
            j = s.find(chr(10), i); i = (j+1) if j != -1 else n; out.append(' ')
        elif s[i:i+2] == '/*':
            j = s.find('*/', i+2); i = (j+2) if j != -1 else n; out.append(' ')
        else:
            out.append(s[i]); i += 1
    return ''.join(out)

def check_module_version():
    """Constants.MODULE_VERSION 必须与 version.properties 的 versionName 一致。

    system_server 的日志只打印这个常量，忘记改就会把「5.7 loaded」打在 5.9 的日志里，
    上次就是这样被误判成「旧代码还在跑」。
    """
    props = 'version.properties'
    consts = os.path.join(ROOT, 'io/github/proify/astrafirmament/lyric/Constants.kt')
    try:
        with open(props, encoding='utf-8') as f:
            want = dict(l.split('=', 1) for l in f.read().splitlines() if '=' in l)['versionName'].strip()
        with open(consts, encoding='utf-8') as f:
            src = f.read()
    except (OSError, KeyError):
        return None
    m = re.search(r'MODULE_VERSION\s*:\s*String\s*=\s*"([^"]+)"', src)
    got = m.group(1).strip() if m else None
    if got != want:
        return f"lyric/Constants.kt: MODULE_VERSION={got!r} 与 version.properties versionName={want!r} 不一致"
    return None


def main():
    errors, warns = [], []
    files = []
    for dirpath, _, fns in os.walk(ROOT):
        for fn in fns:
            if fn.endswith('.kt'): files.append(os.path.join(dirpath, fn))

    for p in files:
        src = open(p, encoding='utf-8').read()
        code = strip_all(src)
        rel = os.path.relpath(p, ROOT)
        # 1. 括号
        if code.count('{') != code.count('}') or code.count('(') != code.count(')'):
            errors.append(f"{rel}: 括号不平衡")
        # 2. 函数行数（简化：从 fun 行起数到配对闭合）
        lines = code.split(chr(10))
        i = 0
        while i < len(lines):
            m = re.search(r'\bfun \w+\(', lines[i])
            if m:
                depth = lines[i].count('{') - lines[i].count('}')
                start = i
                while depth > 0 and i + 1 < len(lines):
                    i += 1
                    depth += lines[i].count('{') - lines[i].count('}')
                if i - start > 60:
                    fname = re.search(r'fun (\w+)', lines[start]).group(1)
                    # UI 视图组装豁免（声明式代码，detekt 惯例）
                    # 歌词链路状态机豁免（切换/锚点/扫描核心——Motion 事故教训红区，
                    # 用户约束"现版本能正常运行的不要大改"，已记录 AUDIT 待人工批）
                    LYRIC_CORE = {'onMetadataChanged','onPlaybackStateChanged','tryLookup',
                                  'onSessionExtras','scanDir','hookLyricModule','collect',
                                  'parseKuwoLrcx','handlePlayerPackage'}
                    # 上游移植件（ui/cupertino：从 Chizuru / compose-hig 原样搬来）保留上游函数形状
                    VENDORED = rel.startswith('io/github/proify/astrafirmament/ui/cupertino/')
                    if VENDORED:
                        pass
                    elif fname == 'buildContent' or fname in LYRIC_CORE:
                        warns.append(f"{rel}: fun {fname} {i-start+1} 行（豁免：{'UI 组装' if fname=='buildContent' else '歌词状态机核心'}）")
                    else:
                        errors.append(f"{rel}: fun {fname} {i-start+1} 行（>60，detekt 强制）")
            i += 1
        # 3. 壳文件
        if re.search(r'^object \w+ \{', code, re.M) and 'fun ' not in code and len(code.split(chr(10))) < 20:
            warns.append(f"{rel}: 壳文件（object 空体）——若被跨文件引用则为事故，若为锚点请注明")
        # 4. 未用 import
        imports = re.findall(r'^import ([\w.]+)', src, re.M)
        for imp in imports:
            short = imp.split('.')[-1]
            body = re.sub(r'^import .*$', '', src, flags=re.M)
            if not re.search(r'\b' + re.escape(short) + r'\b', body):
                warns.append(f"{rel}: 未用 import {imp}")

    version_error = check_module_version()
    if version_error: errors.append(version_error)

    for e in errors: print("ERROR:", e)
    for w in warns: print("WARN:", w)
    print(f"\n检查完成：{len(files)} 文件，{len(errors)} 错误，{len(warns)} 警告")
    sys.exit(1 if errors else 0)

if __name__ == '__main__':
    main()
