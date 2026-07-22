#!/usr/bin/env python3
"""Run correctness-backed performance and resilience evidence profiles."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = {
    "routeEvaluation": ["measuresDeterministicRouteEvaluation"],
    "quotationAcceptanceConcurrency": ["serializesTwentyConcurrentAcceptancesForSharedAndDistinctKeys"],
    "tradeOrderConversionConcurrency": ["createsOneOrderAndOneReliableNextFactAcrossDuplicatesAndConcurrency"],
    "inventoryHotLotContention": ["multipleOrderCallersCompeteWithoutOverselling"],
    "inventoryDistributedContention": ["concurrentCommandsSerializeWithoutNegativeBalancesOrDuplicateEffects"],
    "publicationBacklogAndDuplicates": [
        "drainsDeterministicBacklogWithDuplicateDeliveryAndOneSideEffectEach",
        "completesTheInboxAndNextPublicationInOneTransactionAndSkipsDuplicates",
    ],
    "consumerCrashAndRecovery": ["rollsBackTheHandlerTransactionThenRecordsAndRecoversABoundedRetry"],
    "databaseDeadlockAndRetry": ["classifiesARealPostgresDeadlockForBoundedRetryThenPreservesBalances"],
    "reportingRestartAndRebuild": [
        "deduplicatesEventsAndPreventsOlderStateFromReplacingNewerState",
        "resolvesAClosingEventThatArrivesBeforeItsWorkItem",
        "rebuildsIntoStagingAndAtomicallySwitchesEquivalentResults",
    ],
    "fulfillmentTimeoutAndRecovery": [
        "recordsDeterministicDelayAndFailureAndMarksSlaOnlyOnce",
        "verifiedRecoveryIsIdempotentAndFailureFactsSurviveSourceRollback",
    ],
    "faultControlGuard": [
        "productionDefaultAllowsSuccessButRejectsFailureInjection",
        "approvedDemoProfileExposesDeterministicTimeoutEvidence",
    ],
}


def command_output(command: list[str]) -> str:
    completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True)
    return (completed.stdout + completed.stderr).strip()


def run_logged(command: list[str], log_path: Path, env: dict[str, str] | None = None) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if completed.returncode != 0:
        raise RuntimeError(f"command failed with exit {completed.returncode}; see {log_path.name}")


def java_runtime() -> dict[str, str]:
    version = command_output(["java", "-version"]).splitlines()
    flags = command_output(["java", "-XX:+PrintCommandLineFlags", "-version"])
    collector = re.search(r"-XX:\+Use([A-Za-z0-9]+GC)", flags)
    return {
        "java": version[0],
        "jvm": version[-1],
        "garbageCollector": collector.group(1) if collector else "JVM default (not reported)",
        "commandLineFlags": flags.splitlines()[0],
    }


def docker_runtime() -> dict[str, Any]:
    return {
        "engine": command_output(["docker", "version", "--format", "{{.Server.Version}}"]),
        "compose": command_output(["docker", "compose", "version", "--short"]),
        "cpus": int(command_output(["docker", "info", "--format", "{{.NCPU}}"])),
        "memoryBytes": int(command_output(["docker", "info", "--format", "{{.MemTotal}}"])),
    }


def parse_surefire() -> tuple[dict[str, dict[str, Any]], list[str]]:
    methods: dict[str, dict[str, Any]] = {}
    reports = sorted((ROOT / "backend/target/surefire-reports").glob("TEST-*.xml"))
    for report in reports:
        suite = ET.parse(report).getroot()
        for case in suite.findall("testcase"):
            name = case.attrib["name"]
            if name not in {method for expected in SCENARIOS.values() for method in expected}:
                continue
            methods[name] = {
                "className": case.attrib.get("classname"),
                "durationSeconds": float(case.attrib.get("time", "0")),
                "passed": case.find("failure") is None and case.find("error") is None,
            }
    missing = sorted({method for expected in SCENARIOS.values() for method in expected} - methods.keys())
    return methods, missing


def sanitized_command(command: list[str], output_dir: Path) -> str:
    rendered = " ".join(command)
    return (
        rendered.replace(sys.executable, "python3")
        .replace(str(output_dir), "<result-dir>")
        .replace(str(ROOT), "<repository-root>")
    )


def main() -> None:
    started_at = time.monotonic()
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("smoke", "full"), default="smoke")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skip-identity-outage", action="store_true")
    args = parser.parse_args()

    profile_path = ROOT / "performance/profiles" / f"{args.profile}.json"
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    output_dir = (args.output or ROOT / "target/performance-evidence" / args.profile).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    dataset_dir = output_dir / "dataset"

    commands: list[str] = []
    dataset_command = [
        sys.executable,
        "performance/generate_dataset.py",
        "--profile",
        str(profile_path.relative_to(ROOT)),
        "--output",
        str(dataset_dir),
    ]
    commands.append(sanitized_command(dataset_command, output_dir))
    run_logged(dataset_command, output_dir / "dataset-generation.log")

    benchmark = profile["benchmark"]
    selectors = (
        "RouteEvaluationPerformanceTest,"
        "CustomerQuotationApiIntegrationTest#serializesTwentyConcurrentAcceptancesForSharedAndDistinctKeys,"
        "TradeOrderConversionIntegrationTest#createsOneOrderAndOneReliableNextFactAcrossDuplicatesAndConcurrency,"
        "JdbcAtomicInventoryLotRepositoryConcurrencyTest#multipleOrderCallersCompeteWithoutOverselling,"
        "InventoryReservationOperationsIntegrationTest#concurrentCommandsSerializeWithoutNegativeBalancesOrDuplicateEffects,"
        "LocalEventDeliveryIntegrationTest#drainsDeterministicBacklogWithDuplicateDeliveryAndOneSideEffectEach+completesTheInboxAndNextPublicationInOneTransactionAndSkipsDuplicates+rollsBackTheHandlerTransactionThenRecordsAndRecoversABoundedRetry,"
        "TransactionDeadlockRecoveryIntegrationTest,"
        "AuditReportingIntegrationTest#deduplicatesEventsAndPreventsOlderStateFromReplacingNewerState+resolvesAClosingEventThatArrivesBeforeItsWorkItem+rebuildsIntoStagingAndAtomicallySwitchesEquivalentResults,"
        "FulfillmentOrchestrationIntegrationTest#recordsDeterministicDelayAndFailureAndMarksSlaOnlyOnce,"
        "ExceptionCenterIntegrationTest#verifiedRecoveryIsIdempotentAndFailureFactsSurviveSourceRollback,"
        "SimulatedFulfillmentAdapterTest"
    )
    maven_command = [
        "./mvnw",
        "--batch-mode",
        "--no-transfer-progress",
        "-pl",
        "backend",
        f"-Dtest={selectors}",
        f"-Dcellarbridge.performance.route-warmups={benchmark['routeWarmups']}",
        f"-Dcellarbridge.performance.route-iterations={benchmark['routeIterations']}",
        f"-Dcellarbridge.performance.event-count={benchmark['eventBacklog']}",
        f"-Dcellarbridge.performance.route-output={output_dir / 'route-evaluation.json'}",
        "test",
    ]
    commands.append(sanitized_command(maven_command, output_dir))
    run_logged(maven_command, output_dir / "correctness-scenarios.log")

    methods, missing = parse_surefire()
    if missing:
        raise RuntimeError(f"missing Surefire evidence for: {', '.join(missing)}")
    scenario_results = {}
    for scenario, expected_methods in SCENARIOS.items():
        scenario_results[scenario] = {
            "passed": all(methods[method]["passed"] for method in expected_methods),
            "durationSeconds": sum(methods[method]["durationSeconds"] for method in expected_methods),
            "assertions": expected_methods,
        }
    if not all(result["passed"] for result in scenario_results.values()):
        raise RuntimeError("one or more correctness-backed scenarios failed")
    correctness_log = (output_dir / "correctness-scenarios.log").read_text(encoding="utf-8")
    backlog_match = re.search(
        r"event-backlog seed=(\d+) events=(\d+) deliveries=(\d+) elapsed-ms=([0-9.]+) "
        r"throughput-per-second=([0-9.]+) duplicates=(\d+) side-effects=(\d+)",
        correctness_log,
    )
    if backlog_match is None:
        raise RuntimeError("event backlog metrics were not emitted")
    event_backlog = {
        "seed": int(backlog_match.group(1)),
        "events": int(backlog_match.group(2)),
        "deliveries": int(backlog_match.group(3)),
        "elapsedMs": float(backlog_match.group(4)),
        "throughputPerSecond": float(backlog_match.group(5)),
        "duplicateDeliveries": int(backlog_match.group(6)),
        "sideEffects": int(backlog_match.group(7)),
        "errorRate": 0,
    }

    catalog_dir = output_dir / "catalog-search"
    catalog_env = os.environ.copy()
    catalog_env.update(
        {
            "CATALOG_BENCHMARK_PRODUCTS": str(benchmark["catalogProducts"]),
            "CATALOG_BENCHMARK_WARMUP": str(benchmark["catalogWarmups"]),
            "CATALOG_BENCHMARK_RUNS": str(benchmark["catalogRuns"]),
            "PERFORMANCE_SEED": str(profile["seed"]),
            "PERFORMANCE_PROFILE": args.profile,
            "PERFORMANCE_RESULT_DIR": str(catalog_dir),
        }
    )
    catalog_command = ["./scripts/catalog_search_benchmark.sh"]
    commands.append(sanitized_command(catalog_command, output_dir))
    run_logged(catalog_command, output_dir / "catalog-search.log", catalog_env)
    catalog = json.loads((catalog_dir / "summary.json").read_text(encoding="utf-8"))

    identity_outage: dict[str, Any] = {"executed": False, "reason": "profile-disabled"}
    if profile["faults"]["identityOutage"] and not args.skip_identity_outage:
        identity_env = os.environ.copy()
        identity_env["PERFORMANCE_RESULT_DIR"] = str(output_dir)
        identity_command = ["./scripts/keycloak_outage_evidence.sh"]
        commands.append(sanitized_command(identity_command, output_dir))
        run_logged(identity_command, output_dir / "keycloak-outage.log", identity_env)
        identity_outage = json.loads(
            (output_dir / "keycloak-outage.json").read_text(encoding="utf-8")
        )

    compose_text = (ROOT / "deploy/compose/core.compose.yaml").read_text(encoding="utf-8")
    backend_pom = (ROOT / "backend/pom.xml").read_text(encoding="utf-8")
    redis_absent = re.search(r"(^|[<:/_-])redis([>:/_-]|$)", compose_text + backend_pom, re.I) is None
    if not redis_absent:
        raise RuntimeError("Redis runtime dependency detected; update the outage scenario")

    environment = {
        "revision": command_output(["git", "rev-parse", "HEAD"]),
        "workingTreeDirty": bool(command_output(["git", "status", "--short"])),
        "os": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "logicalCpuCount": os.cpu_count(),
        "python": platform.python_version(),
        **java_runtime(),
        "docker": docker_runtime(),
        "containerLimits": {
            "backend": {"cpus": 1.5, "memoryBytes": 1073741824},
            "frontend": {"cpus": 0.5, "memoryBytes": 134217728},
            "postgres": "Docker Desktop resource pool",
        },
        "profile": profile,
    }
    manifest = json.loads((dataset_dir / "manifest.json").read_text(encoding="utf-8"))
    route = json.loads((output_dir / "route-evaluation.json").read_text(encoding="utf-8"))
    render_command = [
        sys.executable,
        "performance/render_summary.py",
        "--result",
        str(output_dir / "result.json"),
        "--output",
        str(output_dir / "latency-percentiles.svg"),
    ]
    commands.append(sanitized_command(render_command, output_dir))
    artifacts = [
        "dataset/manifest.json",
        "route-evaluation.json",
        "catalog-search/summary.json",
        "catalog-search/catalog-search-plan.json",
        "catalog-search/catalog-unit-filter-plan.json",
        "correctness-scenarios.log",
        "latency-percentiles.svg",
    ]
    if identity_outage.get("executed"):
        artifacts.append("keycloak-outage.json")
    result = {
        "schemaVersion": "cellarbridge.performance-suite.v1",
        "profile": args.profile,
        "seed": profile["seed"],
        "durationBudgetMinutes": profile["durationBudgetMinutes"],
        "environment": environment,
        "dataset": manifest,
        "performance": {
            "catalogSearch": catalog,
            "routeEvaluation": route,
            "eventBacklog": event_backlog,
            "correctnessScenarioDurations": scenario_results,
        },
        "correctnessScenarios": scenario_results,
        "resilience": {
            "keycloakJwkCacheOutage": identity_outage,
            "redisUnavailable": {
                "passed": redis_absent and all(item["passed"] for item in scenario_results.values()),
                "evidence": "Redis is absent from the runtime topology and backend dependencies; core scenarios passed without it.",
            },
            "eventTransport": "PostgreSQL-backed local publication/inbox; no external broker is deployed.",
        },
        "commands": commands,
        "artifacts": artifacts,
    }
    result["durationSeconds"] = time.monotonic() - started_at
    result["withinProfileBudget"] = (
        result["durationSeconds"] <= profile["durationBudgetMinutes"] * 60
    )
    (output_dir / "result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    run_logged(render_command, output_dir / "render-summary.log")
    if not result["withinProfileBudget"]:
        raise RuntimeError(
            f"profile exceeded its {profile['durationBudgetMinutes']}-minute duration budget"
        )
    print(json.dumps({"profile": args.profile, "result": str(output_dir / "result.json")}))


if __name__ == "__main__":
    main()
