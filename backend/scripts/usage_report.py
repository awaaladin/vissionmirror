"""What people actually do, counted: turns the guesses in docs/PRICING.md into real numbers.

    python scripts/usage_report.py              # the last 7 days
    python scripts/usage_report.py --days 14

Reads the per-person counters the API keeps in Redis (a keyed hash of the account or device, never a name or an email) and prints,
per day: people, actions by kind, average and heaviest person. Then the cost per person using the per-action prices below
(edit them to match your bill). Needs REDIS_URL in backend/.env or the environment.
"""

import argparse
import asyncio
import datetime as dt
import os
import sys
from pathlib import Path

LAGOS = dt.timezone(dt.timedelta(hours=1))
# naira per action with Flash-Lite first, the stronger model on hard cases, and the Nigerian voice reading the answer
COST = {"describe": 12.5, "read": 12.5, "ask": 5.9, "assist": 3.0}


def redis_url() -> str:
    if os.environ.get("REDIS_URL"):
        return os.environ["REDIS_URL"]
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            k, _, v = line.partition("=")
            if k.strip() == "REDIS_URL" and v.strip():
                return v.strip().strip('"')
    sys.exit("No REDIS_URL (backend/.env or the environment).")


async def main() -> None:
    from redis.asyncio import Redis

    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=7)
    days = ap.parse_args().days
    r = Redis.from_url(redis_url())
    today = dt.datetime.now(LAGOS)
    everyone: dict[str, float] = {}
    print(f"{'day':10} {'people':>6} {'actions':>8} {'avg/person':>10} {'heaviest':>8}   by kind")
    for back in range(days - 1, -1, -1):
        day = (today - dt.timedelta(days=back)).strftime("%Y%m%d")
        people = {p.decode() for p in await r.smembers(f"usage_people:{day}")}
        total = {k.decode(): int(v) for k, v in (await r.hgetall(f"usage_total:{day}")).items()}
        n = sum(total.values())
        heaviest = 0
        for p in people:
            mine = {k.decode(): int(v) for k, v in (await r.hgetall(f"usage:{day}:{p}")).items()}
            heaviest = max(heaviest, sum(mine.values()))
            everyone[p] = everyone.get(p, 0) + sum(c * COST.get(k, 5) for k, c in mine.items())
        avg = n / len(people) if people else 0
        print(f"{day:10} {len(people):>6} {n:>8} {avg:>10.1f} {heaviest:>8}   {total}")
    if everyone:
        costs = sorted(everyone.values())
        mean = sum(costs) / len(costs)
        print(f"\nOver {days} days, {len(costs)} people. Estimated AI cost per person (naira): "
              f"average {mean:,.0f}, middle {costs[len(costs) // 2]:,.0f}, heaviest {costs[-1]:,.0f}.")
        print(f"Scaled to a month: about {mean / days * 30:,.0f} naira each on average, against 4,332 left of each 4,500 after Paystack's fee.")
    await r.aclose()


if __name__ == "__main__":
    asyncio.run(main())
