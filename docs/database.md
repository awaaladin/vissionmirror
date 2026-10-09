# Database

VisionMirror keeps **one small thing permanently**: user accounts (email, display name, password hash). Photos,
descriptions and conversations never go near it; they live only in Redis for ten minutes.

## This database is shared

The Supabase Postgres used for accounts **also hosts another application** (tables `lessons`, `captions`,
`live_sessions`, `user`, `session`, `account`, ... in the `public` schema, with migration history in the
`drizzle` schema, so it is managed by the Drizzle ORM). Rules that keep the two apart:

1. VisionMirror owns exactly one schema, **`visionmirror`**, and every query uses fully qualified names
   (`visionmirror.users`). It never reads or writes `public`, `drizzle` or Supabase's own schemas.
2. Its table is **not** the `public."user"` table, even though the names look alike.
3. Schema changes are idempotent SQL in `backend/app/services/user_store.py` (`SCHEMA_SQL`), applied only by
   `python backend/scripts/init_db.py`. The running API never changes the schema.
4. Row Level Security is enabled on `visionmirror.users` as defence in depth.
5. The API opens at most 2 connections per server instance, through Supabase's pooler (port 6543), so it cannot
   starve the other app of connections. The driver disables prepared statements for pooler compatibility.
6. Tests never read `.env` and use an in-memory user store, so they cannot touch this database.

If the other app's Drizzle config uses `schemaFilter` or `drizzle-kit push` with schemas beyond `public`,
leave `visionmirror` out of it, otherwise `push` may offer to drop our table.

## Moving to a dedicated database later

Accounts live in one table. To move: create the new database, set `DATABASE_URL`, run `init_db.py`, and either
export/import `visionmirror.users` (`pg_dump -n visionmirror`) or ask people to sign up again. Then update the
`DATABASE_URL` variable in Vercel and `backend/.env`.

## Secrets

`DATABASE_URL` is stored only in `backend/.env` (git-ignored) and as a sensitive Vercel variable. The database
password was shared in a chat, so reset it in Supabase (Project Settings, Database) and update both places.
