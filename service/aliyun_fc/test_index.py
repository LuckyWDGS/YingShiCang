import base64
import datetime
import hashlib
import json
import unittest
import uuid

from index import CAP, Rejected, receive

DAY = datetime.date(2026, 10, 20)


class FakeBucket:
    def __init__(self):
        self.objects = {}

    def put_if_absent(self, key, data, headers):
        if key in self.objects:
            return False
        self.objects[key] = (data, {k.lower(): v for k, v in headers.items()})
        return True

    def meta(self, key):
        return self.objects[key][1]

    def put(self, key, data, headers):
        self.objects[key] = (data, headers)


def event(data, batch=None, digest=None, version="1.0.12", device=None, method="POST", encode=True, auth=None):
    headers = {"X-Batch-Id": batch or str(uuid.uuid4()),
               "X-Content-SHA256": digest or hashlib.sha256(data).hexdigest(),
               "X-App-Version": version}
    if device is not None:
        headers["X-Device-Info"] = base64.urlsafe_b64encode(json.dumps(device).encode()).decode().rstrip("=")
    if auth:
        headers["Authorization"] = auth
    return {"headers": headers, "requestContext": {"http": {"method": method}},
            "isBase64Encoded": encode, "body": base64.b64encode(data).decode() if encode else data.decode()}


class ReceiveTests(unittest.TestCase):
    def setUp(self):
        self.bucket = FakeBucket()

    def test_persists_receipt_and_device_info(self):
        data = b"2026-10-20T00:00:00Z\tapp_start\tok\n"
        request = event(data, device={"model": "TV", "sdk": 34})
        receipt = receive(request, self.bucket, today=DAY)
        batch = request["headers"]["X-Batch-Id"]
        self.assertEqual(receipt, {"batchId": batch, "reportId": batch, "persisted": True,
                                   "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
        key = f"logs/2026-10-20/1.0.12/{batch}.log"
        self.assertEqual(self.bucket.objects[key][0], data)
        device = json.loads(self.bucket.objects[key[:-4] + ".device.json"][0])
        self.assertEqual(device["device"]["model"], "TV")

    def test_plain_text_body_accepted(self):
        data = b"event\n"
        self.assertTrue(receive(event(data, encode=False), self.bucket, today=DAY)["persisted"])

    def test_retry_is_idempotent_and_conflict_rejected(self):
        data = b"event\n"
        batch = str(uuid.uuid4())
        first = receive(event(data, batch=batch), self.bucket, today=DAY)
        self.assertEqual(first, receive(event(data, batch=batch), self.bucket, today=DAY))
        with self.assertRaises(Rejected) as caught:
            receive(event(b"other\n", batch=batch), self.bucket, today=DAY)
        self.assertEqual(caught.exception.status, 409)

    def test_bad_requests_never_stored(self):
        data = b"event\n"
        cases = [event(data, digest="0" * 64), event(data, batch="not-a-uuid"), event(data, version="bad version"),
                 event(b"", digest=hashlib.sha256(b"").hexdigest()), event(data, method="GET")]
        for request in cases:
            with self.assertRaises(Rejected):
                receive(request, self.bucket, today=DAY)
        big = b"x" * (CAP + 1)
        with self.assertRaises(Rejected):
            receive(event(big), self.bucket, today=DAY)
        bad_device = event(data)
        bad_device["headers"]["X-Device-Info"] = "%%%"
        with self.assertRaises(Rejected):
            receive(bad_device, self.bucket, today=DAY)
        self.assertEqual(self.bucket.objects, {})

    def test_optional_token(self):
        data = b"event\n"
        with self.assertRaises(Rejected) as caught:
            receive(event(data), self.bucket, token="secret", today=DAY)
        self.assertEqual(caught.exception.status, 401)
        self.assertTrue(receive(event(data, auth="Bearer secret"), self.bucket, token="secret", today=DAY)["persisted"])


if __name__ == "__main__":
    unittest.main()
