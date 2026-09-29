# Adversarial regression fixture

`adversarial_server.py` exposes the probe inventory used by the integration harness. It is deliberately deterministic and dependency-free so CI can run it on every branch.

The current unit test verifies that the fixture contains the required vectors and that responses are stable. A protocol-level headless Minecraft job can reuse the same inventory and assert the NullTrace contract: each probe must be blocked, normalized, or recorded with an explicit audit decision.
