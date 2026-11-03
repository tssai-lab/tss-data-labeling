import json
import sqlite3
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


DATA_DIR = Path("/mlflow")
DB_PATH = DATA_DIR / "mlflow-lite.db"


def connect():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    connection.execute(
        "create table if not exists experiments ("
        "experiment_id text primary key, name text unique not null, creation_time integer not null)"
    )
    connection.execute(
        "create table if not exists runs ("
        "run_id text primary key, experiment_id text not null, status text not null, "
        "start_time integer not null, end_time integer)"
    )
    connection.execute(
        "create table if not exists metrics ("
        "run_id text not null, key text not null, value real not null, timestamp integer not null, step integer not null)"
    )
    connection.execute(
        "create table if not exists params ("
        "run_id text not null, key text not null, value text not null, primary key (run_id, key))"
    )
    connection.execute(
        "create table if not exists tags ("
        "run_id text not null, key text not null, value text not null, primary key (run_id, key))"
    )
    connection.commit()
    return connection


def now_ms():
    return int(time.time() * 1000)


class Handler(BaseHTTPRequestHandler):
    server_version = "tss-mlflow-lite/1.0"

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        try:
            if parsed.path.endswith("/2.0/mlflow/experiments/get-by-name"):
                self.get_experiment_by_name(query)
            elif parsed.path.endswith("/2.0/mlflow/metrics/get-history-bulk"):
                self.get_metric_history(query)
            else:
                self.send_json({"message": "ok"})
        except Exception as error:
            self.send_json({"error_code": "INTERNAL_ERROR", "message": str(error)}, 500)

    def do_POST(self):
        parsed = urlparse(self.path)
        body = self.read_body()
        try:
            if parsed.path.endswith("/2.0/mlflow/experiments/create"):
                self.create_experiment(body)
            elif parsed.path.endswith("/2.0/mlflow/runs/create"):
                self.create_run(body)
            elif parsed.path.endswith("/2.0/mlflow/runs/log-batch"):
                self.log_batch(body)
            elif parsed.path.endswith("/2.0/mlflow/runs/update"):
                self.update_run(body)
            else:
                self.send_json({"error_code": "ENDPOINT_NOT_FOUND", "message": parsed.path}, 404)
        except Exception as error:
            self.send_json({"error_code": "INTERNAL_ERROR", "message": str(error)}, 500)

    def get_experiment_by_name(self, query):
        name = self.one(query, "experiment_name")
        with connect() as connection:
            row = connection.execute(
                "select * from experiments where name = ?", (name,)
            ).fetchone()
        if row is None:
            self.send_json(
                {"error_code": "RESOURCE_DOES_NOT_EXIST", "message": "experiment not found"},
                404,
            )
            return
        self.send_json(
            {"experiment": {"experiment_id": row["experiment_id"], "name": row["name"]}}
        )

    def create_experiment(self, body):
        name = body.get("name")
        if not name:
            self.send_json({"error_code": "INVALID_PARAMETER_VALUE", "message": "name required"}, 400)
            return
        with connect() as connection:
            row = connection.execute(
                "select experiment_id from experiments where name = ?", (name,)
            ).fetchone()
            if row is not None:
                self.send_json({"experiment_id": row["experiment_id"]})
                return
            experiment_id = str(now_ms())
            connection.execute(
                "insert into experiments(experiment_id, name, creation_time) values (?, ?, ?)",
                (experiment_id, name, now_ms()),
            )
            connection.commit()
        self.send_json({"experiment_id": experiment_id})

    def create_run(self, body):
        run_id = uuid.uuid4().hex
        experiment_id = str(body.get("experiment_id") or "0")
        start_time = int(body.get("start_time") or now_ms())
        with connect() as connection:
            connection.execute(
                "insert into runs(run_id, experiment_id, status, start_time) values (?, ?, ?, ?)",
                (run_id, experiment_id, "RUNNING", start_time),
            )
            for tag in body.get("tags") or []:
                connection.execute(
                    "insert or replace into tags(run_id, key, value) values (?, ?, ?)",
                    (run_id, str(tag.get("key")), str(tag.get("value", ""))),
                )
            connection.commit()
        self.send_json(
            {"run": {"info": {"run_id": run_id, "experiment_id": experiment_id, "status": "RUNNING"}}}
        )

    def log_batch(self, body):
        run_id = body.get("run_id")
        if not run_id:
            self.send_json({"error_code": "INVALID_PARAMETER_VALUE", "message": "run_id required"}, 400)
            return
        with connect() as connection:
            for metric in body.get("metrics") or []:
                connection.execute(
                    "insert into metrics(run_id, key, value, timestamp, step) values (?, ?, ?, ?, ?)",
                    (
                        run_id,
                        str(metric.get("key")),
                        float(metric.get("value")),
                        int(metric.get("timestamp") or now_ms()),
                        int(metric.get("step") or 0),
                    ),
                )
            for parameter in body.get("params") or []:
                connection.execute(
                    "insert or replace into params(run_id, key, value) values (?, ?, ?)",
                    (run_id, str(parameter.get("key")), str(parameter.get("value", ""))),
                )
            for tag in body.get("tags") or []:
                connection.execute(
                    "insert or replace into tags(run_id, key, value) values (?, ?, ?)",
                    (run_id, str(tag.get("key")), str(tag.get("value", ""))),
                )
            connection.commit()
        self.send_json({})

    def update_run(self, body):
        run_id = body.get("run_id")
        status = body.get("status") or "FINISHED"
        end_time = int(body.get("end_time") or now_ms())
        with connect() as connection:
            connection.execute(
                "update runs set status = ?, end_time = ? where run_id = ?",
                (status, end_time, run_id),
            )
            connection.commit()
        self.send_json({"run_info": {"run_id": run_id, "status": status, "end_time": end_time}})

    def get_metric_history(self, query):
        run_id = self.one(query, "run_id")
        metric_key = self.one(query, "metric_key")
        limit = int(self.one(query, "max_results", "10000"))
        with connect() as connection:
            rows = connection.execute(
                "select key, value, timestamp, step from metrics where run_id = ? and key = ? "
                "order by step asc, timestamp asc limit ?",
                (run_id, metric_key, limit),
            ).fetchall()
        self.send_json({"metrics": [dict(row) for row in rows]})

    def read_body(self):
        length = int(self.headers.get("content-length") or 0)
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    @staticmethod
    def one(query, key, default=None):
        values = query.get(key)
        if not values:
            if default is not None:
                return default
            raise ValueError(f"{key} required")
        return values[0]

    def send_json(self, payload, status=200):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, message_format, *args):
        print(message_format % args)


if __name__ == "__main__":
    connect().close()
    ThreadingHTTPServer(("0.0.0.0", 5000), Handler).serve_forever()
