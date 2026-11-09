#!/usr/bin/env python3
"""分析 App 导出的帧级 CSV，按运行模式/状态分组输出 avg / P50 / P95。

用法：
    adb pull /sdcard/Android/data/com.example.androidfacedetector/files/metrics ./metrics
    python tools/analyze_metrics.py metrics/frames_20261109_160000.csv

CSV 由 perf/Metrics.kt 生成，每行一帧：
    requestId,seq,mode,status,faces,liveness,
    e2e_ms,convert_ms,ipc_ms,queue_wait_ms,service_work_ms,
    detect_pre_ms,detect_infer_ms,detect_post_ms,spoof_ms,align_ms,embed_ms,capture_uptime_ms
"""

import csv
import os
import sys
from collections import defaultdict

STATUS_NAME = {
    "0": "OK",
    "1": "DROPPED",
    "2": "TIMEOUT",
    "3": "ERROR",
    "4": "NO_FACE",
}

METRICS = [
    ("e2e_ms", "端到端"),
    ("convert_ms", "帧转换"),
    ("ipc_ms", "IPC往返"),
    ("queue_wait_ms", "排队等待"),
    ("service_work_ms", "服务端处理"),
    ("detect_infer_ms", "检测推理"),
    ("spoof_ms", "活体"),
    ("align_ms", "对齐"),
    ("embed_ms", "特征"),
]


def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    idx = int((len(s) - 1) * p)
    return s[idx]


def summarize(rows, key):
    vals = []
    for r in rows:
        try:
            vals.append(float(r[key]))
        except (ValueError, KeyError):
            pass
    if not vals:
        return None
    return {
        "n": len(vals),
        "avg": sum(vals) / len(vals),
        "p50": pct(vals, 0.5),
        "p95": pct(vals, 0.95),
        "max": max(vals),
    }


def main(path):
    if not os.path.exists(path):
        print("文件不存在:", path)
        sys.exit(1)

    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    if not rows:
        print("CSV 没有数据")
        return

    groups = defaultdict(list)
    for r in rows:
        groups[(r.get("mode", "?"), STATUS_NAME.get(r.get("status", ""), r.get("status", "?")))].append(r)

    print(f"文件: {os.path.basename(path)}   总行数: {len(rows)}")
    for (mode, status), grp in sorted(groups.items()):
        print(f"\n=== mode={mode}  status={status}  ({len(grp)} 帧) ===")
        print(f"{'指标':<12}{'样本':>6}{'avg':>10}{'P50':>10}{'P95':>10}{'max':>10}")
        print("-" * 58)
        for key, label in METRICS:
            s = summarize(grp, key)
            if s is None:
                continue
            print(f"{label:<12}{s['n']:>6}{s['avg']:>10.2f}{s['p50']:>10.2f}{s['p95']:>10.2f}{s['max']:>10.2f}")

    # 丢帧率与活体分布
    total = len(rows)
    dropped = sum(1 for r in rows if r.get("status") == "1")
    fake = sum(1 for r in rows if r.get("liveness") == "0")
    print(f"\n丢帧 {dropped}/{total} = {dropped / total * 100:.1f}%")
    print(f"活体未通过 {fake}/{total}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
