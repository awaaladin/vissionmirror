"""Create the accounts table (idempotent). Run once per database, and again after schema changes.

    python scripts/init_db.py                      # uses DATABASE_URL from backend/.env or the environment

Only ever touches the `visionmirror` schema; other projects sharing the database are not affected.
"""
import asyncio
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import asyncpg  # noqa: E402

from app.core.config import Settings  # noqa: E402
from app.services.user_store import SCHEMA, SCHEMA_SQL, normalize_dsn  # noqa: E402


async def main() -> None:
    dsn = os.environ.get("DATABASE_URL") or Settings(secret_key="x" * 32).database_url
    if not dsn:
        sys.exit("Set DATABASE_URL (in backend/.env or the environment) first.")
    url, ssl = normalize_dsn(dsn)
    conn = await asyncpg.connect(url, ssl=ssl, statement_cache_size=0, timeout=30)
    try:
        await conn.execute(SCHEMA_SQL)
        count = await conn.fetchval(f"SELECT count(*) FROM {SCHEMA}.users")
        print(f"OK: schema '{SCHEMA}' is ready; {count} account(s) so far.")
    finally:
        await conn.close()


asyncio.run(main())
