#!/usr/bin/env python3
"""A JIRA-shaped endpoint: create, comment, list transitions, apply one.

    fake_jira.py <port> <log> [--no-transitions]
"""
import http.server
import json
import sys

port, log_path = int(sys.argv[1]), sys.argv[2]
transitions = [{"id": "11", "name": "In Progress"}, {"id": "31", "name": "Done"}]
args = sys.argv[3:]
while args:
    if args.pop(0) == "--no-transitions":
        transitions = []


class Handler(http.server.BaseHTTPRequestHandler):
    def _record(self, method, body):
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"method": method, "path": self.path, "body": body,
                                  "authorization": self.headers.get("Authorization")}) + "\n")

    def do_GET(self):  # noqa: N802
        self._record("GET", "")
        if self.path.endswith("/transitions"):
            body = json.dumps({"transitions": transitions}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        self._record("POST", raw)
        if self.path.endswith("/issue"):
            body = json.dumps({"id": "10001", "key": "ACME-7",
                               "self": "http://jira.example.com/rest/api/2/issue/10001"}).encode()
        elif self.path.endswith("/comment"):
            body = json.dumps({"id": "20002"}).encode()
        elif self.path.endswith("/transitions"):
            body = b""
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(201 if body else 204)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
