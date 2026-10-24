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

## The checkout on the landing page

The website has a **Support** section (`#support`, linked from the top menu): amount buttons (500, 1,000, 2,000, 5,000, 10,000 naira or another
amount), one email field labelled **"The email you use to sign in to VisionMirror"**, and a **Give with Paystack** button.

1. The page asks for a guest token and then calls `POST /api/support/checkout` with the amount and email. `/api/...` is a same-origin rewrite on the
   website to `https://visionmirror-api.vercel.app/v1/...` (`site/vercel.json`), so the browser needs no special permission.
2. The backend names the transaction `VISIONMIRROR-SUPPORT-...`, puts the email in the metadata (`account_email`), saves a **pending** row in
   `visionmirror.payments` against that email (linked to the account that signs in with it, if there is one) and returns Paystack's page.
3. The person pays on Paystack. Paystack emails the receipt to the same address and sends the browser to `https://visionmirror-app.vercel.app/thanks`.
4. The thank-you page asks `GET /api/support/status?reference=...`, which asks Paystack whether it really succeeded (and that it is VisionMirror's),
   marks the row **paid** with Paystack's amount, fee and time, and answers with the amount and a **masked** email (`a***@gmail.com`).
5. The webhook (below) marks the same row paid too, whichever arrives first. Marking is once-only.

The record is **saved against the sign-in email**, so each gift belongs to one account's owner and can be listed with
`PaymentStore.for_email(email)`. Saving the record is best effort: if the database is down, the gift still goes through (Paystack keeps its own record).

## The endpoints

| Endpoint | What it does |
|---|---|
| `POST /v1/support/checkout` | `{ "amount_ngn": 2000, "email": "..." }` (email optional for a signed-in account). Amount must be 500 to 1,000,000 naira. Returns `{ reference, authorization_url }`; the person pays on Paystack's page. Needs the app's token; rate limited. |
| `GET /v1/support/status?reference=` | for the website's thank-you page: confirms with Paystack, marks the saved payment paid, returns `{paid, amount_ngn, email_hint}`. Public, limited per IP. |
| `GET /v1/support/return` | a plain thank-you page, used only when no website return address is set. It asks Paystack whether the payment really succeeded (reference, status **and** that it is VisionMirror's) before saying thank you. |
| `POST /v1/support/paystack/webhook` | Paystack's `charge.success` event. The signature (HMAC-SHA512 of the raw body with the secret key) is checked first; anything else is rejected with 400. Only VisionMirror's references are acted on. |
| `GET /v1/health` | shows `"payments": true` when the key is loaded (never the key). |

Paying unlocks nothing (the app stays free), so there is nothing to apply on success: the record lives in Paystack.

## Settings (backend `.env` and Vercel)

| Variable | Meaning |
|---|---|
| `PAYSTACK_SECRET_KEY` (or `PAYSTACK_API`) | the live secret key `sk_live_...`. Secret: only `backend/.env` (git-ignored) and Vercel (sensitive). **Never in git, in the app, or in chat.** |
| `PUBLIC_BASE_URL` | `https://visionmirror-api.vercel.app` |
| `SUPPORT_RETURN_URL` | `https://visionmirror-app.vercel.app/thanks`: where Paystack sends the payer after paying (the website's thank-you page). |
| `SUPPORT_MIN_NGN`, `SUPPORT_MAX_NGN` | limits on a gift (default 500 and 1,000,000). |

## The shared webhook

Paystack allows **one webhook URL per account**, and six apps share the account. The router lives in the **Choropia** project
(`payments/webhook_router.py`, at `https://choropia.vercel.app/api/paystack-webhook`). It checks Paystack's signature, works out the app from
`metadata.app` or the reference prefix, and forwards the **exact same body and signature** to that app's webhook, which checks it again.
Where each app's webhook lives is a `ROUTE_<APP>` variable on the Choropia Vercel project; `ROUTE_VISIONMIRROR` is set to
`https://visionmirror-api.vercel.app/v1/support/paystack/webhook`.

Checked end to end with a synthetic signed event: a correctly signed `VISIONMIRROR-` event is accepted by the router and forwarded to this API
(`{"routed": true, "app": "VISIONMIRROR"}`), and a wrongly signed one is refused (401).

Two things only you can confirm:
- the **Paystack dashboard webhook URL** (Settings, API Keys and Webhooks, live mode) is `https://choropia.vercel.app/api/paystack-webhook`;
- Choropia keeps its `PAYSTACK_SECRET_KEY` (its GO_LIVE notes talk about removing old `PAYSTACK_*` variables): the router uses it to check signatures,
  and it must stay the same shared key.

Even without the webhook, a payment is recorded as soon as the person lands on the thank-you page, because that page confirms it with Paystack.

## Launch checklist

- [x] Key in `backend/.env` (git-ignored) and in the Vercel `visionmirror-api` production environment
- [x] `PUBLIC_BASE_URL` set in production
- [x] Every transaction named `VISIONMIRROR-...` with `metadata.app`, enforced in code and tested
- [x] Amount limits, token + rate limit on checkout, signature check on the webhook
- [x] Router rule for `VISIONMIRROR-` (`ROUTE_VISIONMIRROR` on Choropia), tested with a signed event
- [ ] The Paystack dashboard webhook URL points at the Choropia router (yours to check)
- [ ] A first real payment of the smallest amount (500 naira) made by you from the live site, then `python scripts/paystack_report.py` to see it
- [x] A visible way to give: the **Support** section on the landing page
- [ ] Giving from inside the app (not built: the app stays free and offline-first; say if you want a "Support" button there that opens the website section)
