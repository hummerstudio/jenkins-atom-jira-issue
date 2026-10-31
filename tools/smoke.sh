#!/usr/bin/env bash
# Three actions against a fake JIRA: payload shapes, both auth styles, and the transition lookup.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((25000 + RANDOM % 900))
start() {
  python3 tools/fake_jira.py "$port" "$log" "$@" & server=$!
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done
}
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

# run_jira <secrets-json> [key=value ...] — the input JSON is built by python, so quoting cannot bite
run_jira() {
  local secrets="$1"; shift
  local dir; dir="$(mktemp -d)"
  python3 - "$dir/input.json" "$port" "$@" <<'PY'
import json, sys
path, port = sys.argv[1], sys.argv[2]
doc = {"baseUrl": "http://127.0.0.1:" + port, "token": "ignored-when-secret-exists"}
for pair in sys.argv[3:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
  printf '%s' "$secrets" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# create with a bearer token
dir=$(run_jira '{"token": "pat-123"}' action=create projectKey=ACME \
      summary="build 42 failed" description="see console")
status "$dir" | grep -q success || fail "create: $(cat "$dir/output.json")"
[ "$(output "$dir" issueKey)" = "ACME-7" ] || fail "issue key: $(cat "$dir/output.json")"
[ "$(output "$dir" issueUrl)" = "http://jira.example.com/rest/api/2/issue/10001" ] || fail "issue url"
python3 - "$log" <<'PY' || fail "create payload: $(tail -1 "$log")"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
create = [l for l in lines if l["path"].endswith("/rest/api/2/issue")][-1]
assert create["authorization"] == "Bearer pat-123", create["authorization"]
fields = json.loads(create["body"])["fields"]
assert fields["project"]["key"] == "ACME", fields
assert fields["issuetype"]["name"] == "Task", fields
assert fields["summary"] == "build 42 failed", fields
PY

# comment with basic auth: the username is not a secret, so it travels as an input
: >"$log"
dir=$(run_jira '{"token": "pw"}' action=comment issueKey=ACME-7 comment="fix is in" username=ci)
status "$dir" | grep -q success || fail "comment: $(cat "$dir/output.json")"
[ "$(output "$dir" commentId)" = "20002" ] || fail "comment id: $(cat "$dir/output.json")"
python3 - "$log" <<'PY' || fail "comment auth: $(tail -1 "$log")"
import base64, json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
comment = [l for l in lines if l["path"].endswith("/comment")][-1]
assert comment["authorization"] == "Basic " + base64.b64encode(b"ci:pw").decode(), comment["authorization"]
assert json.loads(comment["body"])["body"] == "fix is in"
PY

# transition by name resolves to an id
: >"$log"
dir=$(run_jira '{"token": "pat-123"}' action=transition issueKey=ACME-7 transition="In Progress")
status "$dir" | grep -q success || fail "transition: $(cat "$dir/output.json")"
[ "$(output "$dir" transitionId)" = "11" ] || fail "transition id: $(cat "$dir/output.json")"
python3 - "$log" <<'PY' || fail "transition payload: $(tail -1 "$log")"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
posted = [l for l in lines if l["method"] == "POST" and l["path"].endswith("/transitions")][-1]
assert json.loads(posted["body"])["transition"]["id"] == "11", posted["body"]
PY

# an unknown transition lists the ones that exist
dir=$(run_jira '{"token": "pat-123"}' action=transition issueKey=ACME-7 transition=Reviewed)
status "$dir" | grep -q error || fail "an unknown transition must fail"
grep -q "In Progress, Done" "$dir/output.json" || fail "available transitions: $(cat "$dir/output.json")"

# a numeric id skips the lookup entirely
stop; start --no-transitions
: >"$log"
dir=$(run_jira '{"token": "pat-123"}' action=transition issueKey=ACME-7 transition=31)
status "$dir" | grep -q success || fail "an explicit id should not need the list: $(cat "$dir/output.json")"

# bad input
stop; start
dir=$(run_jira '{"token": "pat-123"}' action=explode)
status "$dir" | grep -q error || fail "unknown action must be refused"
grep -q "known: create" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

dir=$(run_jira '{"token": "pat-123"}' action=create)
status "$dir" | grep -q error || fail "create without a summary must be refused"

echo "smoke ok: create, comment, transition by name and by id, both auth styles, unknown transition, bad input"
