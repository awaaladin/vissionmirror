# Payments (Paystack)

VisionMirror is free. The only money it takes is a **one-time support gift** through Paystack, on the **shared Paystack account**
that VisionMirror uses together with Choropia, Mirror Central, NutriScan, SplitPay (rentsplit) and Voice Verse.

## How every VisionMirror transaction is named

Every transaction made by this codebase carries the same two marks, so it can be told apart from the other five apps in the
Paystack dashboard, in exports, and in the accounts:

| Mark | Value | Where you see it |
|---|---|---|
| **Reference** | `VISIONMIRROR-<PURPOSE>-<YYYYMMDD>-<12 hex>`, e.g. `VISIONMIRROR-SUPPORT-20261023-4F9A2C7B1E0D` | the Reference column, search box and exports |
| **Metadata** | `app: VISIONMIRROR`, `app_name: VisionMirror`, `source: visionmirror-api`, `purpose: SUPPORT` | the transaction's metadata (API and exports) |
| **Custom fields** | `App: VisionMirror`, `Payment for: Support for VisionMirror` | shown on the transaction page of the dashboard |

This is the same convention the other five apps use (their codes: `CHOROPIA`, `MIRROR`, `NUTRISCAN`, `SPLITPAY`, `VOICEVERSE`), so one
filter on the prefix gives one product's money.

**It cannot be forgotten.** `Paystack.initialize` in `backend/app/services/paystack_service.py` refuses to send any transaction whose
reference does not start with `VISIONMIRROR-` or whose `metadata.app` is not `VISIONMIRROR`. A test (`test_support.py`) proves that
nothing reaches Paystack in that case, so a future feature cannot create an unnamed transaction by accident.

## The accounts report

```bash
cd backend
python scripts/paystack_report.py                                   # everything, all time
python scripts/paystack_report.py --from 2026-10-01 --to 2026-10-31 # a month
python scripts/paystack_report.py --csv visionmirror-october.csv    # and a spreadsheet of VisionMirror's transactions
```

It reads the key from `backend/.env` (never printed), asks Paystack for the transactions, **keeps only VisionMirror's** (reference starts
with `VISIONMIRROR-` or `metadata.app` is `VISIONMIRROR`) and prints: how many were paid and how many abandoned, income, Paystack's fees,
income after fees, and income by purpose and by month. Only successful payments count as income.

That is the **income side** of profit and loss. The **costs** are outside Paystack (Gemini, Azure speech, Vercel, Supabase, VoiceVerse):
subtract them to get profit. In the Paystack dashboard the same split is available by searching the reference for `VISIONMIRROR-`.

## The endpoints

| Endpoint | What it does |
|---|---|
| `POST /v1/support/checkout` | `{ "amount_ngn": 2000, "email": "..." }` (email optional for a signed-in account). Amount must be 500 to 1,000,000 naira. Returns `{ reference, authorization_url }`; the person pays on Paystack's page. Needs the app's token; rate limited. |
| `GET /v1/support/return` | where Paystack sends the payer back. It asks Paystack whether the payment really succeeded (reference, status **and** that it is VisionMirror's) before saying thank you. |
| `POST /v1/support/paystack/webhook` | Paystack's `charge.success` event. The signature (HMAC-SHA512 of the raw body with the secret key) is checked first; anything else is rejected with 400. Only VisionMirror's references are acted on. |
| `GET /v1/health` | shows `"payments": true` when the key is loaded (never the key). |

Paying unlocks nothing (the app stays free), so there is nothing to apply on success: the record lives in Paystack.

## Settings (backend `.env` and Vercel)

| Variable | Meaning |
|---|---|
| `PAYSTACK_SECRET_KEY` (or `PAYSTACK_API`) | the live secret key `sk_live_...`. Secret: only `backend/.env` (git-ignored) and Vercel (sensitive). **Never in git, in the app, or in chat.** |
| `PUBLIC_BASE_URL` | `https://visionmirror-api.vercel.app`: where Paystack sends the payer back. |
| `SUPPORT_MIN_NGN`, `SUPPORT_MAX_NGN` | limits on a gift (default 500 and 1,000,000). |

## The shared webhook: one thing only you can set

Paystack allows **one webhook URL per account**, and six apps share the account. The other apps' code says a **webhook router in front of
the account** forwards each event to the right app by reference prefix, passing the body through **untouched** so the signature still
verifies. That router is not in any of these repositories, so it must be told about this app:

> reference starts with `VISIONMIRROR-`  ->  `POST https://visionmirror-api.vercel.app/v1/support/paystack/webhook`

Until that rule exists, payments still work end to end: the payer is sent back to `/v1/support/return`, which confirms the payment with
Paystack itself, and the money is in the account either way. The webhook only adds a log line for each gift.

## Launch checklist

- [x] Key in `backend/.env` (git-ignored) and in the Vercel `visionmirror-api` production environment
- [x] `PUBLIC_BASE_URL` set in production
- [x] Every transaction named `VISIONMIRROR-...` with `metadata.app`, enforced in code and tested
- [x] Amount limits, token + rate limit on checkout, signature check on the webhook
- [ ] Router rule for `VISIONMIRROR-` (above)
- [ ] A first real payment of the smallest amount (500 naira) made by you from the live site, then `python scripts/paystack_report.py` to see it
- [ ] A visible way for people to give (the app and website do not link to checkout yet: tell me the wording and amounts)
