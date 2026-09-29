#!/usr/bin/env python3
"""Deterministic adversarial probe fixture used by CI and local integration tests.

It intentionally does not attempt to emulate the entire Minecraft protocol. A
headless-client harness can point at these endpoints and assert that each
probe is either blocked or normalized by NullTrace before promoting a vector
into a protocol-level test.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

PROBES = (
    "brand", "channel", "known-pack", "translation", "keybind",
    "resource-pack", "redirect-ssrf", "component", "cookie-transfer",
    "combined",
)

class ProbeHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/probes":
            payload = {"probes": list(PROBES), "contract": "blocked-or-normalized"}
            body = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.startswith("/probe/"):
            name = self.path.removeprefix("/probe/")
            if name in PROBES:
                body = json.dumps({"probe": name, "result": "fingerprint-succeeded"}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
        self.send_error(404)

    def log_message(self, *_args):
        pass

def serve(host="127.0.0.1", port=0):
    return ThreadingHTTPServer((host, port), ProbeHandler)

if __name__ == "__main__":
    with serve(host="0.0.0.0", port=25575) as server:
        print("adversarial probe server listening on 25575", flush=True)
        server.serve_forever()
