import json
import threading
import unittest
from urllib.request import urlopen

from adversarial_server import PROBES, serve

class AdversarialFixtureTest(unittest.TestCase):
    def test_all_required_probe_vectors_are_exposed(self):
        with serve() as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            with urlopen(f"http://127.0.0.1:{server.server_port}/probes") as response:
                payload = json.load(response)
            server.shutdown()

        self.assertEqual("blocked-or-normalized", payload["contract"])
        self.assertEqual(set(PROBES), set(payload["probes"]))

    def test_probe_endpoint_is_deterministic(self):
        with serve() as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            with urlopen(f"http://127.0.0.1:{server.server_port}/probe/combined") as response:
                payload = json.load(response)
            server.shutdown()
        self.assertEqual({"probe": "combined", "result": "fingerprint-succeeded"}, payload)

if __name__ == "__main__":
    unittest.main()
