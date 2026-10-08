"""User accounts. Postgres in production (a dedicated `visionmirror` schema), memory in tests.

This is the only place the app keeps anything permanently, and it holds only an email address, a
display name and a password hash. Photos and descriptions never come near it.
"""

import datetime as dt
import uuid
from abc import ABC, abstractmethod
from urllib.parse import urlsplit, urlunsplit, parse_qs

import asyncpg
from pydantic import BaseModel

SCHEMA = "visionmirror"

SCHEMA_SQL = f"""
CREATE SCHEMA IF NOT EXISTS {SCHEMA};
CREATE TABLE IF NOT EXISTS {SCHEMA}.users (
    id            uuid PRIMARY KEY,
    email         text NOT NULL,
    password_hash text NOT NULL,
    display_name  text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower ON {SCHEMA}.users (lower(email));
-- Defence in depth on Supabase: if this schema is ever exposed through its REST API, no row is readable.
ALTER TABLE {SCHEMA}.users ENABLE ROW LEVEL SECURITY;
"""


def normalize_dsn(dsn: str) -> tuple[str, str | None]:
    """Make a libpq-style URL (as Neon, Vercel and Django give out) usable by asyncpg.

    asyncpg treats unknown query parameters as server settings and fails on them, so strip the
    client-only ones (channel_binding, sslmode, and `options`, which other projects use to pin their
    own search_path: this app always writes fully qualified `visionmirror.` names instead).
    Returns the cleaned URL and the ssl mode to pass separately.
    """
    parts = urlsplit(dsn)
    query = parse_qs(parts.query)
    sslmode = (query.get("sslmode") or [None])[0]
    local = (parts.hostname or "") in {"localhost", "127.0.0.1", "::1"}
    # Encrypted unless the URL says otherwise or the database is on this machine: it carries passwords.
    ssl = None if (sslmode == "disable" or (local and sslmode is None)) else "require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", "")), ssl


class User(BaseModel):
    id: uuid.UUID
    email: str
    display_name: str | None = None
    created_at: dt.datetime


class EmailTaken(Exception):
    pass


class AccountsUnavailable(Exception):
    """No database is configured or it cannot be reached."""


class UserStore(ABC):
    @abstractmethod
    async def create(self, email: str, password_hash: str, display_name: str | None) -> User: ...

    @abstractmethod
    async def find_by_email(self, email: str) -> tuple[User, str] | None:
        """Return the user and their password hash, or None."""

    @abstractmethod
    async def get(self, user_id: uuid.UUID) -> User | None: ...

    @abstractmethod
    async def delete(self, user_id: uuid.UUID) -> bool: ...

    async def close(self) -> None:  # pragma: no cover - nothing to close for most stores
        return None


class MemoryUserStore(UserStore):
    def __init__(self) -> None:
        self._users: dict[uuid.UUID, tuple[User, str]] = {}

    async def create(self, email, password_hash, display_name):
        if any(u.email.lower() == email.lower() for u, _ in self._users.values()):
            raise EmailTaken
        user = User(id=uuid.uuid4(), email=email, display_name=display_name, created_at=dt.datetime.now(dt.timezone.utc))
        self._users[user.id] = (user, password_hash)
        return user

    async def find_by_email(self, email):
        return next(((u, h) for u, h in self._users.values() if u.email.lower() == email.lower()), None)

    async def get(self, user_id):
        found = self._users.get(user_id)
        return found[0] if found else None

    async def delete(self, user_id):
        return self._users.pop(user_id, None) is not None


class PostgresUserStore(UserStore):
    """asyncpg with a small lazily-created pool (serverless: each instance holds at most a few connections)."""

    def __init__(self, dsn: str, max_connections: int = 3):
        self.dsn, self.ssl = normalize_dsn(dsn)
        self.max_connections = max_connections
        self._pool: asyncpg.Pool | None = None

    async def _get_pool(self) -> asyncpg.Pool:
        if self._pool is None:
            try:
                self._pool = await asyncpg.create_pool(
                    self.dsn,
                    ssl=self.ssl,
                    min_size=0,
                    max_size=self.max_connections,
                    # Works through pgbouncer-style poolers too.
                    statement_cache_size=0,
                    command_timeout=10,
                    timeout=15,
                )
            except (OSError, asyncpg.PostgresError) as exc:
                raise AccountsUnavailable from exc
        return self._pool

    async def _run(self, fn):
        pool = await self._get_pool()
        try:
            async with pool.acquire() as conn:
                return await fn(conn)
        except (OSError, asyncpg.PostgresConnectionError, asyncpg.UndefinedTableError, TimeoutError) as exc:
            raise AccountsUnavailable from exc

    @staticmethod
    def _user(row) -> User:
        return User(id=row["id"], email=row["email"], display_name=row["display_name"], created_at=row["created_at"])

    async def create(self, email, password_hash, display_name):
        user_id = uuid.uuid4()

        async def op(conn):
            try:
                row = await conn.fetchrow(
                    f"INSERT INTO {SCHEMA}.users (id, email, password_hash, display_name) "
                    "VALUES ($1, $2, $3, $4) RETURNING id, email, display_name, created_at",
                    user_id, email, password_hash, display_name,
                )
            except asyncpg.UniqueViolationError:
                raise EmailTaken from None
            return self._user(row)

        return await self._run(op)

    async def find_by_email(self, email):
        async def op(conn):
            row = await conn.fetchrow(
                f"SELECT id, email, display_name, created_at, password_hash FROM {SCHEMA}.users WHERE lower(email) = lower($1)",
                email,
            )
            return (self._user(row), row["password_hash"]) if row else None

        return await self._run(op)

    async def get(self, user_id):
        async def op(conn):
            row = await conn.fetchrow(
                f"SELECT id, email, display_name, created_at FROM {SCHEMA}.users WHERE id = $1", user_id
            )
            return self._user(row) if row else None

        return await self._run(op)

    async def delete(self, user_id):
        async def op(conn):
            return (await conn.execute(f"DELETE FROM {SCHEMA}.users WHERE id = $1", user_id)).endswith("1")

        return await self._run(op)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None


class NoUserStore(UserStore):
    """Used when no DATABASE_URL is set: accounts are off, guest mode still works."""

    async def create(self, *a, **k):
        raise AccountsUnavailable

    async def find_by_email(self, *a, **k):
        raise AccountsUnavailable

    async def get(self, *a, **k):
        raise AccountsUnavailable

    async def delete(self, *a, **k):
        raise AccountsUnavailable
