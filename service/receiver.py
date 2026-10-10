"""Local/behind-HTTPS log receipt service and release artifact endpoint.

Default loopback binding is for emulator acceptance tests. A non-loopback bind
requires YSC_UPLOAD_TOKEN; terminate TLS and issue short-lived credentials at the
deployment edge. Never expose an unauthenticated public instance.
"""
import argparse
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import pathlib
import re
import sqlite3
import tempfile
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CAP = 10_000_000


class Store:
    def __init__(self, root):
        self.root = pathlib.Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.database = self.root / "receipts.sqlite"
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, sha TEXT, bytes INTEGER, created REAL)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database, timeout=20)
        try:
            with db:
                yield db
        finally:
            db.close()

    def receipt(self, identity):
        with self.connect() as db:
            row = db.execute("SELECT sha,bytes FROM receipts WHERE id=?", (identity,)).fetchone()
        if not row or not (self.root/(identity+".log")).is_file():
            return None
        return dict(batchId=identity, reportId=identity, persisted=True, sha256=row[0], bytes=row[1])

    def save(self, identity, digest, length, source):
        if not re.fullmatch(r"[a-f0-9-]{36}", identity) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Invalid batch metadata")
        if not 0 < length <= CAP:
            raise ValueError("Invalid log size")
        fd, name = tempfile.mkstemp(prefix="incoming-", dir=self.root)
        try:
            remaining, hash_ = length, hashlib.sha256()
            with os.fdopen(fd, "wb") as out:
                while remaining:
                    data = source.read(min(65536, remaining))
                    if not data:
                        raise ValueError("Truncated request")
                    hash_.update(data); out.write(data); remaining -= len(data)
                out.flush(); os.fsync(out.fileno())
            if not hmac.compare_digest(hash_.hexdigest(), digest):
                raise ValueError("Checksum mismatch")
            with self.connect() as db:
                db.execute("BEGIN IMMEDIATE")
                previous = db.execute("SELECT sha,bytes FROM receipts WHERE id=?",(identity,)).fetchone()
                if previous and previous != (digest,length):
                    raise ValueError("Batch identity conflict")
                os.replace(name, self.root/(identity+".log"))
                db.execute("INSERT OR REPLACE INTO receipts VALUES(?,?,?,?)",(identity,digest,length,time.time()))
            return self.receipt(identity)
        finally:
            pathlib.Path(name).unlink(missing_ok=True)

    def prune(self, days=30):
        with self.connect() as db:
            for identity, in db.execute("SELECT id FROM receipts WHERE created<?", (time.time()-days*86400,)).fetchall():
                (self.root/(identity+".log")).unlink(missing_ok=True)
                db.execute("DELETE FROM receipts WHERE id=?",(identity,))


def handler(store, artifacts, token):
    artifacts = pathlib.Path(artifacts).resolve()
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup(); self.connection.settimeout(30)
        def log_message(self, *_):
            pass  # Do not print URL credentials or uploaded contents.
        def respond(self, code, value):
            data=json.dumps(value).encode()
            self.send_response(code); self.send_header("Content-Type","application/json")
            self.send_header("Content-Length",str(len(data)));self.send_header("Cache-Control","no-store")
            self.end_headers();self.wfile.write(data)
        def authorized(self):
            return not token or hmac.compare_digest(self.headers.get("Authorization",""),"Bearer "+token)
        def do_POST(self):
            if self.path != "/logs": return self.respond(404,{"error":"Not found"})
            if not self.authorized(): return self.respond(401,{"error":"Unauthorized"})
            try:
                length=int(self.headers.get("Content-Length","0"))
                receipt=store.save(self.headers.get("X-Batch-Id",""),self.headers.get("X-Content-SHA256",""),length,self.rfile)
                return self.respond(200,receipt)
            except (ValueError,OSError) as error:
                self.close_connection=True
                return self.respond(400,{"error":str(error)})
        def do_GET(self):
            path=urllib.parse.urlparse(self.path).path
            if path.startswith('/receipts/'):
                if not self.authorized():return self.respond(401,{"error":"Unauthorized"})
                receipt=store.receipt(path.rsplit('/',1)[-1])
                return self.respond(200 if receipt else 404, receipt or {"persisted":False})
            if not re.fullmatch(r'/releases/[a-zA-Z0-9_.-]+\.(apk|json)',path):return self.respond(404,{"error":"Not found"})
            file=(artifacts/path.rsplit('/',1)[-1]).resolve()
            if file.parent!=artifacts or not file.is_file():return self.respond(404,{"error":"Not found"})
            self.send_response(200);self.send_header("Content-Length",str(file.stat().st_size))
            self.send_header("Content-Type","application/json" if file.suffix=='.json' else 'application/vnd.android.package-archive')
            self.send_header("Cache-Control","no-cache" if file.suffix=='.json' else 'private')
            self.end_headers()
            with file.open('rb') as src:
                while data:=src.read(65536):self.wfile.write(data)
    return Handler


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument('--host',default='127.0.0.1');p.add_argument('--port',type=int,default=8765)
    p.add_argument('--storage',required=True);p.add_argument('--artifacts',required=True);args=p.parse_args()
    token=os.environ.get('YSC_UPLOAD_TOKEN','')
    if args.host not in {'127.0.0.1','localhost'} and not token:p.error('Non-loopback deployment requires YSC_UPLOAD_TOKEN')
    store=Store(args.storage);store.prune()
    ThreadingHTTPServer((args.host,args.port),handler(store,args.artifacts,token)).serve_forever()
