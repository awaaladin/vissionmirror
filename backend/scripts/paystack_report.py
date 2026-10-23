"""VisionMirror's money on the shared Paystack account: what came in, what Paystack kept, what is left.

    python scripts/paystack_report.py                      # all time
    python scripts/paystack_report.py --from 2026-10-01 --to 2026-10-31
    python scripts/paystack_report.py --csv visionmirror-october.csv

The Paystack account is shared with other apps. VisionMirror's transactions are the ones whose reference starts with
VISIONMIRROR- (and whose metadata.app is VISIONMIRROR); everything else is ignored. Only successful payments count as
income. Amounts are in naira. Costs outside Paystack (AI, hosting) are yours to subtract: this gives the income side.

The key is read from backend/.env (PAYSTACK_SECRET_KEY or PAYSTACK_API) or the environment, and is never printed.
"""

import argparse
import csv
import os
import sys
from collections import defaultdict
from pathlib import Path

import httpx

APP_CODE = "VISIONMIRROR"
BASE = "https://api.paystack.co"


def load_key() -> str:
    for name in ("PAYSTACK_SECRET_KEY", "PAYSTACK_API", "PAYSTACK_SECRET"):
        if os.environ.get(name):
            return os.environ[name].strip()
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() in ("PAYSTACK_SECRET_KEY", "PAYSTACK_API", "PAYSTACK_SECRET") and value.strip():
                return value.strip().strip('"')
    sys.exit("No Paystack key found (PAYSTACK_SECRET_KEY or PAYSTACK_API in backend/.env).")


def is_ours(transaction: dict) -> bool:
    reference = str(transaction.get("reference") or "")
    app = (transaction.get("metadata") or {}).get("app") if isinstance(transaction.get("metadata"), dict) else None
    return reference.upper().startswith(f"{APP_CODE}-") or app == APP_CODE


def summarise(transactions: list[dict]) -> dict:
    """Pure: totals for this app's transactions, by purpose and by month. Amounts in kobo."""
    ours = [t for t in transactions if is_ours(t)]
    paid = [t for t in ours if t.get("status") == "success"]
    by_purpose: dict[str, int] = defaultdict(int)
    by_month: dict[str, int] = defaultdict(int)
    for t in paid:
        meta = t.get("metadata") if isinstance(t.get("metadata"), dict) else {}
        by_purpose[str(meta.get("purpose") or "UNKNOWN")] += int(t.get("amount") or 0)
        by_month[str(t.get("paid_at") or t.get("created_at") or "")[:7]] += int(t.get("amount") or 0)
    gross = sum(int(t.get("amount") or 0) for t in paid)
    fees = sum(int(t.get("fees") or 0) for t in paid)
    return {
        "transactions": len(ours), "paid": len(paid), "not_paid": len(ours) - len(paid),
        "gross_kobo": gross, "fees_kobo": fees, "net_kobo": gross - fees,
        "by_purpose": dict(by_purpose), "by_month": dict(sorted(by_month.items())),
    }


def naira(kobo: int) -> str:
    return f"NGN {kobo / 100:,.2f}"


def fetch_all(key: str, start: str | None, end: str | None) -> list[dict]:
    out: list[dict] = []
    page = 1
    with httpx.Client(timeout=30, headers={"Authorization": f"Bearer {key}"}) as client:
        while True:
            params = {"perPage": 100, "page": page}
            if start:
                params["from"] = start
            if end:
                params["to"] = end
            r = client.get(f"{BASE}/transaction", params=params)
            if r.status_code != 200:
                sys.exit(f"Paystack answered {r.status_code}: {r.json().get('message', '')}")
            body = r.json()
            out += body.get("data", [])
            if page >= int(body.get("meta", {}).get("pageCount", 1) or 1):
                return out
            page += 1


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start", help="YYYY-MM-DD")
    ap.add_argument("--to", dest="end", help="YYYY-MM-DD")
    ap.add_argument("--csv", help="write this app's transactions to a CSV file")
    args = ap.parse_args()
    rows = fetch_all(load_key(), args.start, args.end)
    ours = [t for t in rows if is_ours(t)]
    s = summarise(rows)
    print(f"VisionMirror on the shared Paystack account ({len(rows)} transactions seen, {len(ours)} are VisionMirror's)")
    print(f"  paid: {s['paid']}   not paid or abandoned: {s['not_paid']}")
    print(f"  income (gross):   {naira(s['gross_kobo'])}")
    print(f"  Paystack fees:    {naira(s['fees_kobo'])}")
    print(f"  income after fees: {naira(s['net_kobo'])}")
    for label, table in (("by purpose", s["by_purpose"]), ("by month", s["by_month"])):
        if table:
            print(f"  {label}:")
            for key, kobo in table.items():
                print(f"    {key:<12} {naira(kobo)}")
    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["reference", "status", "amount_ngn", "fees_ngn", "paid_at", "purpose", "email"])
            for t in ours:
                meta = t.get("metadata") if isinstance(t.get("metadata"), dict) else {}
                w.writerow([t.get("reference"), t.get("status"), int(t.get("amount") or 0) / 100, int(t.get("fees") or 0) / 100,
                            t.get("paid_at") or "", meta.get("purpose", ""), (t.get("customer") or {}).get("email", "")])
        print(f"  wrote {args.csv}")


if __name__ == "__main__":
    main()
