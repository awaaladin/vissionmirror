"""Support payments, saved against the email the payer gave (the one they sign in to the app with).

Postgres in production (the `visionmirror.payments` table, in the same schema and through the same small connection pool as accounts),
memory in tests. Saving a record is always best effort: the payment itself is Paystack's, so a database hiccup must never stop
someone giving or stop the thank-you page.
"""

import datetime as dt
import logging
import uuid
from abc import ABC, abstractmethod

from pydantic import BaseModel, PrivateAttr

from app.services.user_store import SCHEMA, AccountsUnavailable, PostgresUserStore, UserStore

log = logging.getLogger("visionmirror.payments")


class PaymentRecord(BaseModel):
    reference: str
    email: str
    user_id: uuid.UUID | None = None
    purpose: str
    amount_kobo: int
    fees_kobo: int | None = None
    status: str = "pending"
    created_at: dt.datetime | None = None
    paid_at: dt.datetime | None = None
    # True only on the call that actually moved the payment to paid (so a receipt goes out exactly once,
    # however many times Paystack and the return page report the same charge).
    _newly_paid: bool = PrivateAttr(default=False)

    @property
    def newly_paid(self) -> bool:
        return self._newly_paid


def mask_email(email: str) -> str:
    """ada.obi@gmail.com -> a***@gmail.com : enough for a person to recognise their own address."""
    local, _, domain = email.partition("@")
    return f"{local[:1]}***@{domain}" if domain else "***"


class PaymentStore(ABC):
    @abstractmethod
    async def record(self, reference: str, email: str, amount_kobo: int, purpose: str, user_id: uuid.UUID | None) -> None: ...

    @abstractmethod
    async def mark_paid(self, reference: str, amount_kobo: int | None, fees_kobo: int | None, paid_at: dt.datetime | None) -> PaymentRecord | None:
        """Mark it paid (once: repeated calls change nothing). None when this app never recorded that reference."""

    @abstractmethod
    async def get(self, reference: str) -> PaymentRecord | None: ...

    @abstractmethod
    async def for_email(self, email: str) -> list[PaymentRecord]: ...


class MemoryPaymentStore(PaymentStore):
    def __init__(self) -> None:
        self.rows: dict[str, PaymentRecord] = {}

    async def record(self, reference, email, amount_kobo, purpose, user_id):
        self.rows[reference] = PaymentRecord(
            reference=reference, email=email.lower(), user_id=user_id, purpose=purpose, amount_kobo=amount_kobo,
            created_at=dt.datetime.now(dt.timezone.utc),
        )

    async def mark_paid(self, reference, amount_kobo, fees_kobo, paid_at):
        row = self.rows.get(reference)
        if row is None:
            return None
        newly = row.status != "success"
        if newly:
            row.status = "success"
            row.amount_kobo = amount_kobo or row.amount_kobo
            row.fees_kobo = fees_kobo
            row.paid_at = paid_at or dt.datetime.now(dt.timezone.utc)
        result = row.model_copy()  # the flag belongs to this call only, never to the stored row
        result._newly_paid = newly
        return result

    async def get(self, reference):
        return self.rows.get(reference)

    async def for_email(self, email):
        return [r for r in self.rows.values() if r.email == email.lower()]


class PostgresPaymentStore(PaymentStore):
    """Uses the accounts store's pool, so the whole app still holds at most two connections to the shared database."""

    def __init__(self, users: PostgresUserStore) -> None:
        self._users = users

    @staticmethod
    def _row(r) -> PaymentRecord:
        return PaymentRecord(**{k: r[k] for k in PaymentRecord.model_fields})

    async def record(self, reference, email, amount_kobo, purpose, user_id):
        async def op(conn):
            await conn.execute(
                f"INSERT INTO {SCHEMA}.payments (reference, email, user_id, purpose, amount_kobo) VALUES ($1, lower($2), $3, $4, $5) "
                "ON CONFLICT (reference) DO NOTHING",
                reference, email, user_id, purpose, amount_kobo,
            )

        await self._users._run(op)

    async def mark_paid(self, reference, amount_kobo, fees_kobo, paid_at):
        async def op(conn):
            row = await conn.fetchrow(
                f"UPDATE {SCHEMA}.payments SET status = 'success', amount_kobo = COALESCE($2, amount_kobo), fees_kobo = $3, "
                "paid_at = COALESCE($4, now()) WHERE reference = $1 AND status <> 'success' "
                "RETURNING reference, email, user_id, purpose, amount_kobo, fees_kobo, status, created_at, paid_at",
                reference, amount_kobo, fees_kobo, paid_at,
            )
            if row is not None:
                record = self._row(row)
                record._newly_paid = True
                return record
            if row is None:  # already paid earlier (or never recorded): return what is there
                row = await conn.fetchrow(
                    f"SELECT reference, email, user_id, purpose, amount_kobo, fees_kobo, status, created_at, paid_at "
                    f"FROM {SCHEMA}.payments WHERE reference = $1", reference,
                )
            return self._row(row) if row else None

        return await self._users._run(op)

    async def get(self, reference):
        async def op(conn):
            row = await conn.fetchrow(
                f"SELECT reference, email, user_id, purpose, amount_kobo, fees_kobo, status, created_at, paid_at "
                f"FROM {SCHEMA}.payments WHERE reference = $1", reference,
            )
            return self._row(row) if row else None

        return await self._users._run(op)

    async def for_email(self, email):
        async def op(conn):
            rows = await conn.fetch(
                f"SELECT reference, email, user_id, purpose, amount_kobo, fees_kobo, status, created_at, paid_at "
                f"FROM {SCHEMA}.payments WHERE lower(email) = lower($1) ORDER BY created_at DESC", email,
            )
            return [self._row(r) for r in rows]

        return await self._users._run(op)


class NoPaymentStore(PaymentStore):
    """No database: payments still work (Paystack keeps the record); nothing is saved here."""

    async def record(self, *a, **k):
        return None

    async def mark_paid(self, *a, **k):
        return None

    async def get(self, *a, **k):
        return None

    async def for_email(self, *a, **k):
        return []


def build_payment_store(users: UserStore) -> PaymentStore:
    if isinstance(users, PostgresUserStore):
        return PostgresPaymentStore(users)
    from app.services.user_store import MemoryUserStore

    return MemoryPaymentStore() if isinstance(users, MemoryUserStore) else NoPaymentStore()


async def best_effort(coro, what: str):
    """Run a payment-record call; log and carry on if the database is unavailable, so giving never fails because of it."""
    try:
        return await coro
    except AccountsUnavailable:
        log.warning("payment record not saved (%s): database unavailable", what)
    except Exception:  # noqa: BLE001 - the record is secondary to the payment
        log.exception("payment record failed (%s)", what)
    return None
