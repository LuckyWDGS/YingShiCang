"""Aliyun Function Compute 3.0 log receiver that stores uploads in a private OSS bucket.

The app POSTs one diagnostic batch with X-Batch-Id (UUID), X-Content-SHA256 and
X-App-Version, plus optional X-Device-Id (anonymous "XXXX-XXXX" install ID) and
X-Device-Info (base64url JSON). This function verifies the checksum, writes the
batch to OSS without overwriting a different batch, and returns the receipt the
app requires before it clears local logs.

Layout (kept permanently; one folder per device):
  logs/<device id>/device.json                         latest device profile
  logs/<device id>/<UTC date>/<version>_<batch>.log     sanitized diagnostic log
  logs/<device id>/<UTC date>/<version>_<batch>.device.json
Uploads without a device ID (apps older than 1.1.1) go under logs/unknown/.

No AccessKey lives here or in the app: the function uses the temporary
credentials of the RAM role bound to it (context.credentials).

Environment:
  OSS_BUCKET    bucket name, e.g. ying-shi-cang
  OSS_ENDPOINT  same-region internal endpoint, e.g. https://oss-cn-hangzhou-internal.aliyuncs.com
  UPLOAD_TOKEN  optional; when set, requests must send "Authorization: Bearer <token>"
"""
import base64
import datetime
import hashlib
import hmac
import json
import os
import re

CAP = 10_000_000
DEVICE_CAP = 8192
BATCH = re.compile(r"[a-f0-9-]{36}")
DIGEST = re.compile(r"[a-f0-9]{64}")
VERSION = re.compile(r"[0-9A-Za-z._+-]{1,40}")
DEVICE_ID = re.compile(r"[0-9A-Z]{4}-[0-9A-Z]{4}")


class Rejected(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def _headers(event):
    return {str(k).lower(): str(v) for k, v in (event.get("headers") or {}).items()}


def _body(event):
    body = event.get("body") or ""
    if event.get("isBase64Encoded"):
        return base64.b64decode(body)
    return body.encode("utf-8") if isinstance(body, str) else bytes(body)


def _device(value):
    if not value:
        return None
    if len(value) > DEVICE_CAP:
        raise Rejected(400, "Device info too large")
    try:
        data = json.loads(base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)))
    except Exception:
        raise Rejected(400, "Invalid device info")
    if not isinstance(data, dict):
        raise Rejected(400, "Invalid device info")
    return data


def _ascii(value, limit=64):
    """OSS user metadata must be plain ASCII; keep it short and printable."""
    return re.sub(r"[^ -~]", "?", str(value or ""))[:limit]


def receive(event, bucket, token="", now=None):
    """Validate one upload and persist it through `bucket` (an oss2.Bucket-like object)."""
    method = ((event.get("requestContext") or {}).get("http") or {}).get("method", "POST")
    if method != "POST":
        raise Rejected(405, "POST only")
    headers = _headers(event)
    if token and not hmac.compare_digest(headers.get("authorization", ""), "Bearer " + token):
        raise Rejected(401, "Unauthorized")
    batch = headers.get("x-batch-id", "")
    digest = headers.get("x-content-sha256", "")
    version = headers.get("x-app-version", "")
    device_id = headers.get("x-device-id", "")
    if not BATCH.fullmatch(batch) or not DIGEST.fullmatch(digest) or not VERSION.fullmatch(version):
        raise Rejected(400, "Invalid batch metadata")
    if device_id and not DEVICE_ID.fullmatch(device_id):
        raise Rejected(400, "Invalid device id")
    device = _device(headers.get("x-device-info", ""))
    data = _body(event)
    if not 0 < len(data) <= CAP:
        raise Rejected(400, "Invalid log size")
    if not hmac.compare_digest(hashlib.sha256(data).hexdigest(), digest):
        raise Rejected(400, "Checksum mismatch")

    now = now or datetime.datetime.now(datetime.timezone.utc)
    folder = f"logs/{device_id or 'unknown'}"
    key = f"{folder}/{now.date().isoformat()}/{version}_{batch}.log"
    meta = {"x-oss-meta-sha256": digest, "x-oss-meta-bytes": str(len(data)), "x-oss-meta-app-version": version,
            "x-oss-meta-device-id": device_id or "unknown"}
    if device is not None:
        meta["x-oss-meta-model"] = _ascii(f"{device.get('brand', '')} {device.get('model', '')}".strip())
        meta["x-oss-meta-android"] = _ascii(device.get("android", ""))
    if not bucket.put_if_absent(key, data, dict(meta, **{"Content-Type": "text/plain; charset=utf-8"})):
        existing = bucket.meta(key)
        if existing.get("x-oss-meta-sha256") != digest or existing.get("x-oss-meta-bytes") != str(len(data)):
            raise Rejected(409, "Batch identity conflict")
    if device is not None:
        info = json.dumps({"deviceId": device_id or "unknown", "batchId": batch, "appVersion": version,
                           "uploadedAt": now.isoformat(timespec="seconds"), "logKey": key, "device": device},
                          ensure_ascii=False, indent=1).encode("utf-8")
        json_type = {"Content-Type": "application/json; charset=utf-8"}
        bucket.put(key[:-4] + ".device.json", info, json_type)
        if device_id:
            bucket.put(f"{folder}/device.json", info, json_type)
    return {"batchId": batch, "reportId": batch, "deviceId": device_id or "unknown", "persisted": True,
            "sha256": digest, "bytes": len(data)}


class OssBucket:
    """Adapter over oss2 so receive() stays testable without the SDK."""

    def __init__(self, context):
        import oss2  # Built into the Function Compute Python runtime.
        creds = context.credentials
        auth = oss2.StsAuth(creds.access_key_id, creds.access_key_secret, creds.security_token)
        self._oss2 = oss2
        self._bucket = oss2.Bucket(auth, os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"])

    def put_if_absent(self, key, data, headers):
        try:
            self._bucket.put_object(key, data, headers=dict(headers, **{"x-oss-forbid-overwrite": "true"}))
            return True
        except self._oss2.exceptions.ServerError as error:
            if error.status == 409:
                return False
            raise

    def meta(self, key):
        return {k.lower(): v for k, v in self._bucket.head_object(key).headers.items()}

    def put(self, key, data, headers):
        self._bucket.put_object(key, data, headers=headers)


def _response(status, value):
    return {"statusCode": status, "headers": {"Content-Type": "application/json", "Cache-Control": "no-store"},
            "body": json.dumps(value)}


def handler(event, context):
    try:
        request = json.loads(event)
    except Exception:
        return _response(400, {"error": "Invalid request"})
    try:
        return _response(200, receive(request, OssBucket(context), os.environ.get("UPLOAD_TOKEN", "")))
    except Rejected as error:
        return _response(error.status, {"error": str(error)})
    except Exception:
        # Never echo uploaded content or credentials; the app keeps its logs and retries later.
        return _response(500, {"error": "Storage unavailable"})
