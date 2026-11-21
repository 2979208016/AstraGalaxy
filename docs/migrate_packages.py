#!/usr/bin/env python3
"""RISKY 批次一：包名收敛执行脚本（需用户批准后运行！）

改动面（dry-run 实测）：
- 16 个文件的 package 声明改为目录对应包
- 全项目 20 处 import（lyric.* 7 处 → hook.cimu/hook.core/shared 分流；capability.link 13 处中 LinkHook/SystemLog 相关改 hook.*）
- 每步后自动跑 checks.py 验证；任一步失败立即停机可回滚（5.2 基线目录兜底）

用法：python3 docs/migrate_packages.py --apply
      python3 docs/migrate_packages.py --dry   （默认：只打印改动清单）
"""
import os, re, sys, shutil, subprocess

ROOT = "app/src/main/kotlin/io/github/proify/astrafirmament"
BACKUP = "/sdcard/xinghe_pkg_backup"  # 执行前整树备份

PKG_MAP = {  # 文件 → (新 package, 旧 package)
    "hook/cimu/LocalLyricProvider.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/XingHeLyricProvider.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/KuwoLyricProvider.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/SpotifyLyricProvider.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/RnLyricBridge.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/HttpLyricSniffer.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/KugouLyricLoader.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/cimu/PlayerTitle.kt": "io.github.proify.astrafirmament.hook.cimu",
    "hook/core/HookRegistry.kt": "io.github.proify.astrafirmament.hook.core",
    "hook/core/HookCrashLog.kt": "io.github.proify.astrafirmament.hook.core",
    "hook/core/ModuleLogger.kt": "io.github.proify.astrafirmament.hook.core",
    "hook/core/SystemLog.kt": "io.github.proify.astrafirmament.hook.core",
    "hook/LinkHook.kt": "io.github.proify.astrafirmament.hook",
    "shared/SuReader.kt": "io.github.proify.astrafirmament.shared",
    "shared/Qrc.kt": "io.github.proify.astrafirmament.shared",
    "shared/KuwoStreamTee.kt": "io.github.proify.astrafirmament.shared",
}

# 类名 → 新包（import 改写映射；同包引用不需要 import）
CLASS_TO_PKG = {}
for path, pkg in PKG_MAP.items():
    CLASS_TO_PKG[os.path.basename(path)[:-3]] = pkg

def all_kt():
    for dirpath, _, fns in os.walk(ROOT):
        for fn in fns:
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)

def run(apply=False):
    if apply:
        if os.path.exists(BACKUP):
            shutil.rmtree(BACKUP)
        shutil.copytree(ROOT, BACKUP)
        print("已备份到", BACKUP)
    for path, new_pkg in PKG_MAP.items():
        f = os.path.join(ROOT, path)
        src = open(f, encoding="utf-8").read()
        old_pkg = re.match(r"package (\S+)", src).group(1)
        print(f"{path}: {old_pkg} -> {new_pkg}")
        if apply and old_pkg != new_pkg:
            src = src.replace(f"package {old_pkg}", f"package {new_pkg}", 1)
            open(f, "w", encoding="utf-8").write(src)
    # import 改写：所有文件中 import 旧包.类 → import 新包.类
    if apply:
        for f in all_kt():
            src = open(f, encoding="utf-8").read()
            orig = src
            for cls, new_pkg in CLASS_TO_PKG.items():
                src = re.sub(
                    rf"import io\.github\.proify\.astrafirmament\.(lyric|capability\.link)\.{cls}\b",
                    f"import {new_pkg}.{cls}", src)
            if src != orig:
                open(f, "w", encoding="utf-8").write(src)
                print("import 更新:", os.path.relpath(f, ROOT))
    if apply:
        r = subprocess.run([sys.executable, "docs/checks.py"], capture_output=True, text=True)
        print(r.stdout[-500:])
        if "ERROR" in r.stdout:
            print("!!! 检查失败，回滚:", BACKUP)
            shutil.rmtree(ROOT)
            shutil.copytree(BACKUP, ROOT)
            sys.exit(1)

if __name__ == "__main__":
    run(apply="--apply" in sys.argv)
