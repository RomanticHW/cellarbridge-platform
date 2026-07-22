#!/usr/bin/env python3
"""Render a deterministic latency summary from a performance result."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def _metric_panel(
    title: str,
    unit: str,
    metrics: dict[str, Any],
    keys: tuple[str, str, str],
    y: int,
) -> list[str]:
    values = [float(metrics[key]) for key in keys]
    if any(value < 0 for value in values):
        raise ValueError(f"{title} contains a negative percentile")
    maximum = max(values) or 1.0
    lines = [f'<text x="40" y="{y}" class="panel-title">{title}</text>']
    labels = ("p50", "p95", "p99")
    for index, (label, value) in enumerate(zip(labels, values, strict=True)):
        row_y = y + 32 + index * 34
        width = round(430 * value / maximum, 2)
        lines.extend(
            [
                f'<text x="40" y="{row_y + 15}" class="label">{label}</text>',
                f'<rect x="85" y="{row_y}" width="{width}" height="20" rx="4" class="bar"/>',
                f'<text x="{95 + width}" y="{row_y + 15}" class="value">{value:.3f} {unit}</text>',
            ]
        )
    return lines


def render(result: dict[str, Any]) -> str:
    if result.get("schemaVersion") != "cellarbridge.performance-suite.v1":
        raise ValueError("unsupported performance result schema")
    if not all(item.get("passed") for item in result["correctnessScenarios"].values()):
        raise ValueError("refusing to chart a result with failed correctness scenarios")

    catalog = result["performance"]["catalogSearch"]["metrics"]
    route = result["performance"]["routeEvaluation"]
    profile = result["profile"]
    duration = float(result["durationSeconds"])
    lines = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="760" height="480" viewBox="0 0 760 480" role="img" aria-labelledby="title description">',
        '<title id="title">Measured latency percentiles</title>',
        f'<desc id="description">{profile} profile latency summary rendered from result.json</desc>',
        "<style>",
        "  .background { fill: #f7f4ed; }",
        "  .title { fill: #17211b; font: 700 22px system-ui, sans-serif; }",
        "  .subtitle { fill: #526056; font: 13px system-ui, sans-serif; }",
        "  .panel-title { fill: #17211b; font: 600 16px system-ui, sans-serif; }",
        "  .label, .value { fill: #334039; font: 12px ui-monospace, monospace; }",
        "  .bar { fill: #39755a; }",
        "</style>",
        '<rect width="760" height="480" rx="16" class="background"/>',
        '<text x="40" y="48" class="title">Measured latency percentiles</text>',
        f'<text x="40" y="72" class="subtitle">Profile: {profile} · duration: {duration:.3f}s · correctness errors: 0</text>',
    ]
    lines.extend(
        _metric_panel(
            "Catalog mixed search",
            "ms",
            catalog,
            ("p50Ms", "p95Ms", "p99Ms"),
            112,
        )
    )
    lines.append(
        f'<text x="40" y="248" class="subtitle">Throughput: {float(catalog["throughputPerSecond"]):.3f} queries/s</text>'
    )
    lines.extend(
        _metric_panel(
            "Deterministic route evaluation",
            "μs",
            route,
            ("p50Micros", "p95Micros", "p99Micros"),
            292,
        )
    )
    lines.append(
        f'<text x="40" y="428" class="subtitle">Throughput: {float(route["throughputPerSecond"]):.3f} operations/s</text>'
    )
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = json.loads(args.result.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(result), encoding="utf-8")


if __name__ == "__main__":
    main()
