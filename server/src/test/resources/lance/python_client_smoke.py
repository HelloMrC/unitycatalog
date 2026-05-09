#!/usr/bin/env python3
import argparse
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

import pyarrow as pa


ARROW_FILE = "application/vnd.apache.arrow.file"
ARROW_STREAM = "application/vnd.apache.arrow.stream"


class UnityCatalogLanceClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def health(self):
        return self.post_json("/admin/worker/health", {})

    def insert(self, table_id):
        table = pa.table({"id": [101, 102], "text": ["python-alpha", "python-beta"]})
        sink = io.BytesIO()
        with pa.ipc.new_stream(sink, table.schema) as writer:
            writer.write_table(table)
        return self.post_bytes(
            f"/v1/table/{self.path(table_id)}/insert",
            sink.getvalue(),
            ARROW_STREAM,
            "application/json",
        )

    def count_rows(self, table_id):
        return self.post_json(f"/v1/table/{self.path(table_id)}/count_rows", {})

    def query(self, table_id):
        body = self.post_bytes(
            f"/v1/table/{self.path(table_id)}/query",
            b"{}",
            "application/json",
            ARROW_FILE,
            parse_json=False,
        )
        return self.read_arrow(body)

    def post_json(self, path, payload):
        return self.post_bytes(
            path,
            json.dumps(payload).encode(),
            "application/json",
            "application/json",
        )

    def post_bytes(self, path, body, content_type, accept, parse_json=True):
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method="POST",
            headers={"content-type": content_type, "accept": accept},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"{path} failed with {exc.code}: {exc.read().decode()}") from exc
        return json.loads(data.decode()) if parse_json else data

    def read_arrow(self, body):
        for opener in (pa.ipc.open_file, pa.ipc.open_stream):
            try:
                return opener(io.BytesIO(body)).read_all()
            except Exception:
                pass
        raise RuntimeError("query response was not Arrow IPC")

    def path(self, table_id):
        return urllib.parse.quote(table_id, safe="$")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--table-id", required=True)
    args = parser.parse_args()

    client = UnityCatalogLanceClient(args.base_url)
    health = client.health()
    insert = client.insert(args.table_id)
    count = client.count_rows(args.table_id)
    result = client.query(args.table_id)
    summary = {
        "connectOk": health.get("worker") == "healthy",
        "insertOk": insert.get("realLanceDbWorker") is True,
        "queryOk": result.num_rows == 2,
        "count": count,
        "queryRows": result.num_rows,
        "columns": result.column_names,
    }
    print(json.dumps(summary, separators=(",", ":")))
    return 0 if all([summary["connectOk"], summary["insertOk"], summary["queryOk"]]) else 1


if __name__ == "__main__":
    sys.exit(main())
