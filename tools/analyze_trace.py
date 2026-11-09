#!/usr/bin/env python3
"""解析 atrace 文本输出，统计各埋点区间的耗时，并还原一次完整帧的事件链。

用法：
    adb shell atrace -t 5 -a com.example.androidfacedetector \\
        sched view gfx binder_driver -b 8192 > trace.txt
    python tools/analyze_trace.py trace.txt

atrace 文本每行形如：
    cam-analysis-0-5556  ( 5375) [004] d..2. 12345.678901: tracing_mark_write: B|5375|frame.convert
                                             ^^^^^^^^^^^^^ 时间戳(秒)
    ... tracing_mark_write: E|5375
"""

import re
import sys
from collections import defaultdict

LINE = re.compile(
    r"^\s*(?P<task>\S+?)-(?P<tid>\d+)\s+\(\s*(?P<pid>\d+)\)\s+\[(?P<cpu>\d+)\].*?"
    r"(?P<ts>\d+\.\d+):\s+tracing_mark_write:\s+(?P<payload>.*)$"
)
B = re.compile(r"^B\|(\d+)\|(.+)$")
E = re.compile(r"^E\|(\d+)$")
C = re.compile(r"^C\|(\d+)\|(.+)\|(\d+)$")


APP_PREFIXES = ("frame.", "ipc.", "infer.", "svc.", "ui.")


def is_app(name):
    return name.startswith(APP_PREFIXES)


def main(path, only_app=True):
    stacks = defaultdict(list)   # tid -> [(name, ts)]
    durations = defaultdict(list)  # name -> [ms]
    events = []                  # (ts, tid, kind, name)

    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            m = LINE.search(line)
            if not m:
                continue
            ts = float(m.group("ts"))
            payload = m.group("payload").strip()
            tid = int(m.group("tid"))   # task 名后缀是线程 id，括号里才是进程 id

            mb = B.match(payload)
            if mb:
                stacks[tid].append((mb.group(2), ts))
                events.append((ts, tid, "B", mb.group(2)))
                continue
            me = E.match(payload)
            if me:
                if stacks[tid]:
                    name, t0 = stacks[tid].pop()
                    durations[name].append((ts - t0) * 1000.0)
                    events.append((ts, tid, "E", name))
                continue
            mc = C.match(payload)
            if mc:
                events.append((ts, tid, "C", mc.group(2)))

    if not durations:
        print("没有解析到任何 B/E 埋点，请确认 atrace 时带了 -a <包名>")
        return

    items = [(n, d) for n, d in durations.items() if (is_app(n) or not only_app)]
    if not items:
        print("没有应用自定义埋点，试试 --all 查看全部区间")
        items = list(durations.items())

    print(f"{'区间':<22}{'次数':>6}{'平均ms':>10}{'P50':>9}{'P95':>9}{'最大ms':>10}")
    print("-" * 68)
    total_pairs = 0
    for name, ds in sorted(items, key=lambda kv: -len(kv[1])):
        ds_sorted = sorted(ds)
        n = len(ds_sorted)
        avg = sum(ds_sorted) / n
        p50 = ds_sorted[int((n - 1) * 0.5)]
        p95 = ds_sorted[int((n - 1) * 0.95)]
        total_pairs += n
        print(f"{name:<22}{n:>6}{avg:>10.2f}{p50:>9.2f}{p95:>9.2f}{max(ds_sorted):>10.2f}")
    print("-" * 68)
    print(f"共 {total_pairs} 个完整区间")

    # 还原一条事件链：找一次 frame.convert 开始后、100ms 内出现的其它区间
    converts = [e for e in events if e[2] == "B" and e[3] == "frame.convert"]
    if converts:
        print("\n单帧事件链示例（以 frame.convert 起点为 0ms）：")
        for t0, tid0, _, _ in converts[:1]:
            window = [e for e in events if t0 <= e[0] <= t0 + 0.3 and is_app(e[3])]
            base = t0
            for ts, tid, kind, name in window:
                offset = (ts - base) * 1000
                mark = "开始" if kind == "B" else ("结束" if kind == "E" else "瞬时")
                print(f"  +{offset:7.2f}ms  tid={tid:<6} {mark}  {name}")
    else:
        print("\n未找到 frame.convert 埋点")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], only_app="--all" not in sys.argv)
