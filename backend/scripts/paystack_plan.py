"""Make (or find) VisionMirror's monthly Paystack plan, once, and print its code.

    python scripts/paystack_plan.py            # shows the plan if it exists, otherwise creates it
    python scripts/paystack_plan.py --price 4500

Put the printed PLN_... code in the PAYSTACK_PLAN_CODE environment variable (Vercel) so the website can offer a card
subscription that renews monthly. The plan is named "VISIONMIRROR monthly" so it is recognisable on the shared account.
The key is read like paystack_report.py does and is never printed.
"""

import argparse
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from paystack_report import BASE, load_key  # noqa: E402

NAME = "VISIONMIRROR monthly"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--price", type=int, default=4500, help="naira per month")
    args = ap.parse_args()
    headers = {"Authorization": f"Bearer {load_key()}"}
    with httpx.Client(base_url=BASE, headers=headers, timeout=30) as http:
        plans = http.get("/plan", params={"perPage": 100}).json().get("data") or []
        for p in plans:
            if p.get("name") == NAME and not p.get("is_deleted") and not p.get("is_archived"):
                print(f"Exists: {p['plan_code']}  {p['amount'] // 100:,} naira {p['interval']}")
                if p["amount"] != args.price * 100:
                    print(f"  (the price differs from {args.price:,}: Paystack lets you change it on the dashboard)")
                return
        r = http.post("/plan", json={"name": NAME, "amount": args.price * 100, "interval": "monthly", "currency": "NGN",
                                     "description": "VisionMirror, one price for everyone"}).json()
        if not r.get("status"):
            sys.exit(f"Paystack refused: {r.get('message')}")
        print(f"Created: {r['data']['plan_code']}  {args.price:,} naira monthly")


if __name__ == "__main__":
    main()
