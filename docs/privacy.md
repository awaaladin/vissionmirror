# Privacy

- **Photos are processed in memory only.** They are never written to the API container's disk. (The web framework normally spools uploads over 1 MB to a temporary file; the app turns that off, and a test fails if it ever happens again.)
- **Sessions** hold the photo and conversation in Redis for 10 minutes, then Redis deletes them. Each follow-up question restarts the 10 minutes; `DELETE /v1/session/{id}` removes everything immediately.
- **Redis persistence is off** in `docker-compose.yml` (no RDB or AOF files), so nothing survives a Redis restart.
- **No accounts, no personal data.** A random device UUID is the only identifier. Sessions are readable only by the device that created them.
- **Logs carry no payloads.** One JSON line per request: request ID, HTTP method, route template (not the raw path, so no session IDs), status, duration, and a short one-way hash of the device. Never images, descriptions, questions, answers, tokens or API keys. Tests check this. Rate-limit counters in Redis are keyed by a one-way hash of the IP or device and expire within a minute.
- **Metadata is stripped.** Every photo is decoded and re-encoded in memory before use, which removes EXIF data such as GPS location and device details.
- **Third party:** with `VISION_PROVIDER=anthropic` or `gemini`, each photo is sent to that provider for analysis. With `mock`, nothing leaves the server.
- **Gemini free tier warning:** Google's free API tier may use submitted content to improve its products. That is fine for development and the competition demo with your own photos, but use a paid tier (or Anthropic) before real users upload photos.
