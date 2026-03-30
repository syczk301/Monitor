from __future__ import annotations

import argparse
import statistics
import time

import httpx


def run_stress(base_url: str, seconds: int, concurrency: int) -> None:
    latencies: list[float] = []
    end = time.time() + seconds
    with httpx.Client(base_url=base_url, timeout=5.0) as client:
        while time.time() < end:
            start = time.perf_counter()
            for _ in range(concurrency):
                res = client.get("/api/health")
                res.raise_for_status()
            elapsed = (time.perf_counter() - start) * 1000
            latencies.append(elapsed / concurrency)
    if latencies:
        print(
            {
                "requests": len(latencies) * concurrency,
                "avg_latency_ms": round(statistics.mean(latencies), 2),
                "p95_latency_ms": round(statistics.quantiles(latencies, n=20)[18], 2),
                "max_latency_ms": round(max(latencies), 2),
            }
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--concurrency", type=int, default=20)
    args = parser.parse_args()
    run_stress(base_url=args.base_url, seconds=args.seconds, concurrency=args.concurrency)
