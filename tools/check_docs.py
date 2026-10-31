#!/usr/bin/env python3
"""文档与源码一致性检查（一条命令，输出只报问题）。

背景：这个工程的数值曾被写在 README / 技能笔记三处，漂移过 9 处（见 git log）。
现在数值只写在 docs/tuning.md，本脚本是它的守卫；也适合挂在 CI 上。

三项检查：
  1. 常量对表   docs/tuning.md 表格里的 `<CONST>` 值 vs src 里 `static final` 的实际值
                （含"幻名"：文档写了源码里根本不存在的常量）
  2. 死资源     每个 png 是否有引用；models/item 的贴图键是否都命中文件
  3. 产物可复现 重跑两个生成器到临时目录，与仓库现有产物逐字节比对
                （gen_item_icons.py 由本脚本代跑；gen_assets.py 需 PIL，可用 --skip-assets 跳过）

用法:
  python tools/check_docs.py                 # 全跑
  python tools/check_docs.py --skip-assets   # 跳过需要 PIL 的贴图生成器
退出码: 0 = 全通过，1 = 有问题（CI 可直接用）
"""
import argparse
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "skis")
MODELS = os.path.join(RES, "models", "item")
TUNING = os.path.join(ROOT, "docs", "tuning.md")

problems = []


def num(s):
    m = re.search(r"[-−]?\d+(?:\.\d+)?", s or "")
    return float(m.group(0).replace("−", "-")) if m else None


# ---------------------------------------------------------------- 1. 常量对表
def source_constants():
    out = {}
    for dirpath, _, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if not f.endswith(".java"):
                continue
            txt = open(os.path.join(dirpath, f), encoding="utf-8").read()
            for name, val in re.findall(
                r"(?m)^\s*(?:public |private |protected )?static final\s+[\w<>\.\[\]]+\s+([A-Z0-9_]+)\s*=\s*([^;]+);",
                txt,
            ):
                v = re.match(r"^([-\d\.]+)[DF]?$", val.strip())
                out.setdefault(name, []).append(float(v.group(1)) if v else val.strip())
    return out


def check_constants(tuning_path=None):
    tuning = tuning_path or TUNING
    if not os.path.exists(tuning):
        problems.append(f"找不到常量表 {tuning}")
        return 0
    src = source_constants()
    checked = 0
    for lineno, line in enumerate(open(tuning, encoding="utf-8").read().split("\n"), 1):
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        names = re.findall(r"`([A-Z][A-Z0-9_]+)`", cells[0])
        if not names or len(cells) < 2:
            continue
        # 值列：表有两形状 —— `| 常量 | 值 | 作用 |` 与 `| 常量 | 位置 | 值 | 作用 |`。
        # 取「第一个含数字、且不是反引号包住的标识（那是位置列）」的单元格。
        valcell = next((c for c in cells[1:]
                        if not c.startswith("`") and re.search(r"[-−]?\d", c)), None)
        if valcell is None:
            continue
        vals = [num(x) for x in re.split(r"[/、]", valcell) if num(x) is not None]
        for i, name in enumerate(names):
            if i >= len(vals):
                continue
            checked += 1
            if name not in src:
                problems.append(f"tuning.md:{lineno} 幻名 {name}（源码里没有这个常量）")
                continue
            # 只比数值常量：布尔 / 枚举类常量（如 `SINGLE_SIDE_SPRAY = true`、`SNOW = ParticleTypes...`）
            # 在源码里是字符串，拿去减会抛 TypeError —— 它们的值列没有数字，本来也进不到这里。
            numeric = [v for v in src[name] if isinstance(v, (int, float))]
            if numeric and not any(abs(v - vals[i]) < 1e-9 for v in numeric):
                problems.append(f"tuning.md:{lineno} {name} 文档 {vals[i]} != 源码 {numeric}")
    return checked


# ---------------------------------------------------------------- 2. 死资源
def check_assets():
    text = ""
    for dirpath, _, files in os.walk(os.path.join(ROOT, "src")):
        for f in files:
            if os.path.splitext(f)[1] in (".java", ".json"):
                text += open(os.path.join(dirpath, f), encoding="utf-8", errors="ignore").read()
    pngs, dead = [], []
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if f.endswith(".png"):
                pngs.append(os.path.join(dirpath, f))
                if os.path.splitext(f)[0] not in text and f not in text:
                    dead.append(os.path.relpath(os.path.join(dirpath, f), ROOT))
    for p in sorted(dead):
        problems.append(f"未被引用的贴图 {p}")
    # 模型贴图键必须命中文件
    for f in sorted(os.listdir(MODELS)):
        if not f.endswith(".json"):
            continue
        spec = json.load(open(os.path.join(MODELS, f), encoding="utf-8"))
        for key, val in (spec.get("textures") or {}).items():
            if not val.startswith("skis:"):
                continue
            rel = val.split(":", 1)[1]
            hit = [c for c in (
                os.path.join(RES, "textures", rel + ".png"),
                os.path.join(RES, rel + ".png"),
            ) if os.path.exists(c)]
            if not hit:
                problems.append(f"{f} 的贴图键 {key}={val} 找不到文件")
    return len(pngs)


# ---------------------------------------------------------------- 3. 可复现性
def _fresh_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check_regenerable(include_assets=True, tmp=None):
    import contextlib
    import io as _io

    regen = 0
    # gen_item_icons：重定向 write_json 到临时目录再逐字节比对
    try:
        mod = _fresh_module(os.path.join(ROOT, "tools", "gen_item_icons.py"), "check_docs_icons")
        written = []

        def spy(path, data, _w=written, _t=tmp):
            name = os.path.basename(path)
            _w.append(name)
            with open(os.path.join(_t, name), "w", encoding="utf-8", newline="\n") as fh:
                json.dump(data, fh, ensure_ascii=False, indent=2)
                fh.write("\n")

        mod.write_json = spy
        with contextlib.redirect_stdout(_io.StringIO()):
            mod.main()
        for name in written:
            a = open(os.path.join(tmp, name), encoding="utf-8").read()
            b = open(os.path.join(MODELS, name), encoding="utf-8").read()
            regen += 1
            if a != b:
                problems.append(f"重跑生成器得到不同的 {name}（生成器与产物不配套）")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if include_assets:
        try:
            from PIL import Image  # noqa: F401
        except ImportError:
            print("  跳过贴图生成器：环境里没有 PIL（也可用 --skip-assets）")
            return regen
        # 把模块里的输出路径常量指到 tmp 镜像，避免跑检查时弄脏工作区
        mod = _fresh_module(os.path.join(ROOT, "tools", "gen_assets.py"), "check_docs_assets")
        mirror = os.path.join(tmp, "skis")
        mod.ASSETS = mirror
        mod.TEX_ITEM = os.path.join(mirror, "textures", "item")
        mod.TEX_ENTITY = os.path.join(mirror, "textures", "entity")
        mod.TEX_ARMOR = os.path.join(mod.TEX_ENTITY, "equipment", "humanoid")
        mod.ICON = os.path.join(mirror, "icon.png")
        with contextlib.redirect_stdout(_io.StringIO()):
            mod.main()
        for dirpath, _, files in os.walk(mirror):
            for f in files:
                if not f.endswith(".png"):
                    continue
                made = os.path.join(dirpath, f)
                repo = os.path.join(RES, os.path.relpath(made, mirror))
                regen += 1
                if not os.path.exists(repo):
                    problems.append(f"gen_assets.py 多写出一个未入库的 {os.path.relpath(repo, ROOT)}")
                elif open(made, "rb").read() != open(repo, "rb").read():
                    problems.append(f"重跑 gen_assets.py 改了 {os.path.relpath(repo, ROOT)}")
    return regen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-assets", action="store_true", help="跳过需要 PIL 的贴图生成器")
    ap.add_argument("--tuning", help="常量表路径（默认 docs/tuning.md；自测时可指向副本）")
    args = ap.parse_args()

    # 临时目录放仓库内的 build/ 下：gen_assets 打印相对路径，跨盘 relpath 会抛错
    build = os.path.join(ROOT, "build")
    os.makedirs(build, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="check_docs_", dir=build)
    try:
        n_const = check_constants(args.tuning)
        n_png = check_assets()
        n_regen = check_regenerable(include_assets=not args.skip_assets, tmp=tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"常量对表 {n_const} 项 · 贴图 {n_png} 个 · 生成器复现 {n_regen} 个文件")
    if problems:
        print(f"\n发现 {len(problems)} 个问题：")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("全部一致 ✅")


if __name__ == "__main__":
    main()
