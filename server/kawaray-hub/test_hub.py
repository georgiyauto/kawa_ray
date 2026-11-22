import importlib.util
import json
import tempfile
import unittest
import threading
import urllib.request
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("hub", Path(__file__).with_name("hub.py"))
hub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hub)


class Reports(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        hub.STATE = self.temp.name
        hub.DB_PATH = str(Path(self.temp.name) / "reports.db")
        hub.UPDATES = str(Path(self.temp.name) / "updates")
        hub.init_db()

    def tearDown(self):
        self.temp.cleanup()

    def report(self, allowed=True):
        return {"report_id": "KW-0123456789ABCDEF", "kind": "panic", "version": "0.4.9",
                "message": "test crash", "device": {"model": "Test PC", "ram_bytes": 8*1024**3},
                "privacy": {"device_info_and_ip": allowed}}

    def test_consented_ip_is_server_observed_and_retry_is_idempotent(self):
        report = self.report()
        report["client_ip"] = "1.2.3.4"
        first = hub.store(report, "8.8.4.4")
        self.assertEqual(first, hub.store(report, "9.9.9.9"))
        with hub.db() as c:
            payload = json.loads(c.execute("SELECT payload FROM reports").fetchone()[0])
            self.assertEqual(payload["client_ip"], "8.8.4.4")
            self.assertEqual(c.execute("SELECT COUNT(*) FROM reports").fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT COUNT(*) FROM notifications").fetchone()[0], 1)

    def test_legacy_or_unconsented_report_never_stores_ip(self):
        rid = hub.store(self.report(False), "8.8.4.4")
        with hub.db() as c:
            payload = json.loads(c.execute("SELECT payload FROM reports WHERE id=?", (rid,)).fetchone()[0])
            self.assertNotIn("client_ip", payload)

    def test_provider_ip_is_separate_validated_and_requires_new_consent(self):
        r = self.report()
        r["privacy"]["provider_ip_outside_vpn"] = True
        r["provider_ip"] = {"address": "8.8.8.8", "source": "kawa_hub_physical_uplink", "observed_at": int(hub.time.time())}
        rid = hub.store(r, "172.19.0.2")
        with hub.db() as c:
            payload = json.loads(c.execute("SELECT payload FROM reports WHERE id=?", (rid,)).fetchone()[0])
        self.assertEqual(payload["provider_ip"]["verification"], "client_reported")
        self.assertNotIn("client_ip", payload)
        self.assertIn("IP провайдера (со слов приложения): 8.8.8.8", hub.notification_text(rid, payload))
        r["report_id"] = "KW-0123456789ABCDE0"
        del r["privacy"]["provider_ip_outside_vpn"]
        rid = hub.store(r, "8.8.4.4")
        with hub.db() as c:
            payload = json.loads(c.execute("SELECT payload FROM reports WHERE id=?", (rid,)).fetchone()[0])
        self.assertNotIn("provider_ip", payload)

    def test_invalid_provider_ip_fields_are_removed(self):
        for i, address in enumerate(["172.19.0.2", "127.0.0.1", "100.64.0.1", "224.0.0.1", "bad", {"ip":"8.8.8.8"}]):
            r = self.report(); r["report_id"] = f"KW-{i:016X}"
            r["privacy"]["provider_ip_outside_vpn"] = True
            r["provider_ip"] = {"address": address, "observed_at": int(hub.time.time()), "source": "kawa_hub_physical_uplink"}
            r["observed_ip"] = "8.8.8.8"
            rid = hub.store(r, "172.19.0.2")
            with hub.db() as c:
                payload = json.loads(c.execute("SELECT payload FROM reports WHERE id=?", (rid,)).fetchone()[0])
            self.assertNotIn("provider_ip", payload)
            self.assertNotIn("observed_ip", payload)
            self.assertNotIn("client_ip", payload)

    def test_lookup_is_no_store_and_refuses_private_observed_ip(self):
        server = hub.ThreadingHTTPServer(("127.0.0.1", 0), hub.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            req = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/v1/ip", headers={"X-Real-IP": "8.8.8.8"})
            with urllib.request.urlopen(req) as r:
                self.assertEqual(json.loads(r.read())["ip"], "8.8.8.8")
                self.assertEqual(r.headers["Cache-Control"], "no-store")
            req = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/v1/ip", headers={"X-Real-IP": "172.19.0.2"})
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(req)
            self.assertEqual(error.exception.code, 503)
            with hub.db() as c:
                self.assertEqual(c.execute("SELECT COUNT(*) FROM reports").fetchone()[0], 0)
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_delivery_failure_stays_queued_and_success_is_not_sent_again(self):
        hub.store(self.report(), "8.8.4.4")
        with patch.object(hub, "send_telegram", side_effect=OSError("offline")):
            hub.deliver_pending()
        with hub.db() as c:
            self.assertEqual(c.execute("SELECT delivered FROM notifications").fetchone()[0], 0)
            c.execute("UPDATE notifications SET next_attempt=0")
        with patch.object(hub, "send_telegram", return_value=None) as send:
            hub.deliver_pending()
            hub.deliver_pending()
            self.assertEqual(send.call_count, 1)
        with hub.db() as c:
            self.assertEqual(c.execute("SELECT delivered FROM notifications").fetchone()[0], 1)

    def test_notification_includes_device_and_ip_without_parse_mode_injection(self):
        rid = hub.store(self.report(), "8.8.4.4")
        with hub.db() as c:
            payload = json.loads(c.execute("SELECT payload FROM reports").fetchone()[0])
        text = hub.notification_text(rid, payload)
        self.assertIn("8.8.4.4", text)
        self.assertIn("Test PC", text)
        self.assertIn("0.4.9", text)

    def test_http_upload_and_served_asset_are_identical(self):
        server = hub.ThreadingHTTPServer(("127.0.0.1", 0), hub.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            req = urllib.request.Request(base+"/v1/report", data=json.dumps(self.report()).encode(), headers={"Content-Type": "application/json", "X-Real-IP": "8.8.4.4"})
            with urllib.request.urlopen(req) as r:
                self.assertEqual(r.status, 201)
                self.assertEqual(json.loads(r.read())["id"], "KW-0123456789ABCDEF")
            path = Path(hub.UPDATES) / "files" / "KawaVPN-test.exe"
            path.write_bytes(b"installer test")
            with urllib.request.urlopen(base+"/v1/update/files/KawaVPN-test.exe") as r:
                self.assertEqual(r.read(), path.read_bytes())
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def test_external_request_cannot_spoof_forwarded_ip(self):
        handler = object.__new__(hub.Handler)
        handler.client_address = ("8.8.4.4", 12345)
        handler.headers = {"X-Real-IP": "1.2.3.4"}
        self.assertEqual(handler.client_ip(), "8.8.4.4")

    def test_manifest_signature_is_bound_to_manifest_digest(self):
        server = hub.ThreadingHTTPServer(("127.0.0.1", 0), hub.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True);worker.start()
        signatures=Path(hub.UPDATES)/"signatures";signatures.mkdir()
        digest="a"*64;(signatures/(digest+".sig")).write_bytes(b"old-signature")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{server.server_port}/v1/update/manifest.sig?sha256={digest}") as r:
                self.assertEqual(r.read(), b"old-signature")
        finally:
            server.shutdown();server.server_close();worker.join()


if __name__ == "__main__":
    unittest.main()
