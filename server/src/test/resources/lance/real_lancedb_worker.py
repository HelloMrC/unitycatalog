#!/usr/bin/env python3
import argparse
import base64
import hashlib
import io
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


def require_deps():
    missing = []
    for module in ("lancedb", "pyarrow"):
        try:
            __import__(module)
        except Exception:
            missing.append(module)
    if missing:
        raise RuntimeError("missing Python modules: " + ", ".join(missing))


def sample_arrow_stream():
    import pyarrow as pa

    table = pa.table({"id": [1, 2], "text": ["alpha", "beta"]})
    sink = io.BytesIO()
    with pa.ipc.new_stream(sink, table.schema) as writer:
        writer.write_table(table)
    return sink.getvalue()


def read_arrow_table(body):
    import pyarrow as pa

    for opener in (pa.ipc.open_stream, pa.ipc.open_file):
        try:
            return opener(io.BytesIO(body)).read_all()
        except Exception:
            pass
    raise ValueError("Invalid Arrow IPC payload.")


def arrow_file_bytes(table):
    import pyarrow as pa

    sink = io.BytesIO()
    with pa.ipc.new_file(sink, table.schema) as writer:
        writer.write_table(table)
    return sink.getvalue()


def attr(obj, name):
    value = getattr(obj, name)
    return value() if callable(value) else value


def schema_json(schema):
    fields = [{"name": field.name, "type": str(field.type)} for field in schema]
    return json.dumps({"fields": fields}, separators=(",", ":"))


class State:
    def __init__(self, root):
        import lancedb

        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.db = lancedb.connect(str(self.root))

    def name(self, command):
        table = command.get("table") or {}
        key = table.get("pathKey") or table.get("path_key") or command.get("pathKey")
        return "uc_" + hashlib.sha1(str(key or "unknown").encode()).hexdigest()

    def uri(self, name):
        return (self.root / f"{name}.lance").resolve().as_uri()

    def exists(self, name):
        try:
            self.db.open_table(name)
            return True
        except Exception:
            return False

    def stats(self, table):
        size = sum(file.stat().st_size for file in self.root.rglob("*") if file.is_file())
        return {
            "totalBytes": size,
            "numRows": int(table.count_rows()),
            "numIndices": 0,
            "fragmentStats": {},
        }


class Handler(BaseHTTPRequestHandler):
    state = None

    def do_GET(self):
        if self.path_only() == "/internal/lance/v1/health":
            self.json(200, {"worker": "real-lancedb-worker", "status": "ok"})
            return
        self.json(404, {"type": "not_found", "message": "Unknown worker path."})

    def do_POST(self):
        path = self.path_only()
        try:
            if path.startswith("/internal/lance/v1/commands/"):
                self.handle_command(path.rsplit("/", 1)[-1], self.read_json())
            elif path.startswith("/internal/lance/v1/arrow/"):
                self.arrow(path.rsplit("/", 1)[-1], self.read_headers(), self.read_body())
            else:
                self.json(404, {"type": "not_found", "message": "Unknown worker path."})
        except Exception as exc:
            self.json(500, {"type": "worker_error", "message": str(exc)})

    def handle_command(self, operation, command):
        table = self.state.db.open_table(self.state.name(command))
        if operation == "query":
            self.bytes(200, "application/vnd.apache.arrow.file", arrow_file_bytes(attr(table, "to_arrow")))
        elif operation == "count_rows":
            self.json(200, self.envelope({"count": int(table.count_rows())}))
        elif operation == "stats":
            self.json(200, self.envelope(self.state.stats(table)))
        else:
            self.json(400, {"type": "unsupported_operation", "message": operation})

    def arrow(self, operation, command, body):
        if operation != "insert":
            self.json(400, {"type": "unsupported_operation", "message": operation})
            return
        name = self.state.name(command)
        data = read_arrow_table(body)
        if self.state.exists(name):
            table = self.state.db.open_table(name)
            table.add(data)
        else:
            table = self.state.db.create_table(name, data=data)
        version = int(attr(table, "version"))
        self.json(
            200,
            self.envelope(
                {
                    "transactionId": f"real-lancedb-{version}",
                    "version": version,
                    "arrowSchemaJson": schema_json(attr(table, "schema")),
                    "stats": self.state.stats(table),
                    "storageLocation": self.state.uri(name),
                    "tableUri": self.state.uri(name),
                }
            ),
        )

    def envelope(self, payload):
        result = dict(payload)
        result["backendType"] = "worker-http"
        result["realLanceDbWorker"] = True
        return result

    def read_json(self):
        body = self.read_body()
        return json.loads(body.decode()) if body else {}

    def read_headers(self):
        command = self.decode_header("x-uc-lance-attributes")
        command["operation"] = self.headers.get("x-uc-lance-command")
        command["context"] = self.decode_header("x-uc-lance-context")
        command["table"] = self.decode_header("x-uc-lance-table")
        command["storage"] = self.decode_header("x-uc-lance-storage")
        return command

    def decode_header(self, name):
        value = self.headers.get(name)
        if not value:
            return {}
        padded = value + "=" * (-len(value) % 4)
        return json.loads(base64.urlsafe_b64decode(padded).decode())

    def read_body(self):
        if "chunked" in self.headers.get("transfer-encoding", "").lower():
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    return b"".join(chunks)
                size = int(line.split(b";", 1)[0], 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    return b"".join(chunks)
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
        return self.rfile.read(int(self.headers.get("content-length", "0")))

    def json(self, status, payload):
        self.bytes(status, "application/json", json.dumps(payload).encode())

    def bytes(self, status, content_type, body):
        self.send_response(status)
        self.send_header("content-type", content_type)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def path_only(self):
        return urlparse(self.path).path

    def log_message(self, format, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--sample-arrow", action="store_true")
    parser.add_argument("--root", default="/tmp/uc-lance-real-worker")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    try:
        require_deps()
        if args.check:
            return 0
        if args.sample_arrow:
            sys.stdout.buffer.write(base64.b64encode(sample_arrow_stream()))
            return 0
        Handler.state = State(args.root)
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
        print(f"READY {server.server_address[1]}", flush=True)
        server.serve_forever()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
