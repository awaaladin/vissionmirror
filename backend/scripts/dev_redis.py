"""Redis-protocol server for local dev without Docker or a Redis install.

Run:  python scripts/dev_redis.py     (listens on 127.0.0.1:6379)
In-memory only, like the real thing with persistence off. Not for production.
"""
from fakeredis import TcpFakeServer

if __name__ == "__main__":
    server = TcpFakeServer(("127.0.0.1", 6379), server_type="redis")
    print("dev redis on 127.0.0.1:6379")
    server.serve_forever()
