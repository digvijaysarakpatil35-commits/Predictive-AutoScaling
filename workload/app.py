"""Minimal CPU-bound HTTP workload for the closed-loop demo.

Each GET / does a fixed chunk of CPU work (repeated SHA-256 hashing) and returns
200. The amount of work is set by WORK_ITERATIONS so it can be calibrated: more
iterations = more CPU per request = fewer requests to saturate a replica. Kept to
the Python standard library so the image is tiny and has no dependencies.
"""
import hashlib
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# CPU work per request. Calibrate on the target machine (see the plan) so that at
# baseline replicas, mid-load utilization sits near the scaling target.
WORK_ITERATIONS = int(os.environ.get("WORK_ITERATIONS", "40"))
PORT = int(os.environ.get("PORT", "8080"))

# A large buffer is deliberate: hashlib releases the GIL while hashing inputs this
# size, so concurrent requests burn CPU in parallel across threads. With a tiny
# buffer the GIL would serialize them — latency would explode but CPU would stay
# low, and CPU is exactly the signal the autoscaler reads.
_BUFFER = os.urandom(262144)  # 256 KB


def _burn_cpu(iterations: int) -> None:
    for _ in range(iterations):
        hashlib.sha256(_BUFFER).digest()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (http.server API)
        _burn_cpu(WORK_ITERATIONS)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"ok\n")

    # Silence the default per-request stderr logging (too noisy under load).
    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"workload listening on :{PORT} (WORK_ITERATIONS={WORK_ITERATIONS})", flush=True)
    server.serve_forever()