#!/bin/bash
#  Copyright 2026 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
# Runs a built image through every environment contract the README promises and fails on the first
# broken promise. Usage: test/smoke-test.sh <image>. Needs docker, curl, awk and jq.
#
# The reasoner sections run the conformance suite from reasoner/src/test/resources/conformance
# through the image's real launcher, and prove that a worker's heap exhaustion, timeout or kill
# never disturbs Fuseki, in a container whose --memory is the minimum the memory budget allows.

set -euo pipefail

IMAGE="${1:?usage: smoke-test.sh <image>}"
PORT="${SMOKE_PORT:-3399}"
BASE="http://127.0.0.1:$PORT"
PASSWORD="smoke-admin"
GRAPH="https://open-metadata.org/graph/knowledge"
CONTAINER="fuseki-smoke-$$"
VOLUME="fuseki-smoke-$$"
FAILURES=0
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="$HERE/../reasoner/src/test/resources/conformance"
REASONER=/jena-fuseki/reasoner/bin/reasoner
JOBS=/fuseki/reasoning/jobs
REASONING=(-e OPENMETADATA_EXTENSION_ENABLED=true -e OPENMETADATA_REASONING_ENABLED=true
  -e OPENMETADATA_REASONER_HEAP=1g -e OPENMETADATA_REASONER_PAGE_CACHE_FLOOR=64m)
# (H_f 512m + O_f 1g + H_w 1g + O_w 512m + C_min 64m) / 0.9, rounded up to whole MiB.
MINIMUM_MEMORY=3485m

cleanup() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  docker volume rm -f "$VOLUME" > /dev/null 2>&1 || true
}
trap cleanup EXIT

check() {
  local description="$1"
  shift
  if "$@"; then
    echo "PASS  $description"
  else
    echo "FAIL  $description"
    FAILURES=$((FAILURES + 1))
  fi
}

fresh_volume() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  docker volume rm -f "$VOLUME" > /dev/null
}

start() {
  docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" -p "127.0.0.1:$PORT:3030" -v "$VOLUME:/fuseki" \
    -e ADMIN_PASSWORD="$PASSWORD" -e FUSEKI_HEAP=512m "$@" "$IMAGE" > /dev/null
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$BASE/\$/ping"; then
      return 0
    fi
    sleep 1
  done
  docker logs "$CONTAINER"
  echo "FAIL  server did not become ready"
  exit 1
}

options_headers() {
  curl -s -D- -o /dev/null -u "admin:$PASSWORD" -X OPTIONS "$BASE/$1/data" | tr -d '\r'
}

header_value() {
  options_headers "$1" | awk -v name="$2" 'tolower($1) == tolower(name ":") { print $2 }'
}

has_no_extension_headers() {
  ! options_headers "$1" | grep -qi '^X-OpenMetadata-'
}

has_request_id() {
  options_headers "$1" | grep -qi '^Fuseki-Request-Id:'
}

header_equals() {
  [ "$(header_value "$1" "$2")" = "$3" ]
}

union_is() {
  local dataset="$1" expected="$2" probe="urn:smoke:$RANDOM"
  curl -fs -o /dev/null -u "admin:$PASSWORD" -X POST -H 'Content-Type: application/sparql-update' \
    --data "INSERT DATA { GRAPH <urn:smoke:graph> { <$probe> <urn:smoke:p> \"v\" } }" "$BASE/$dataset/update"
  local answer
  answer=$(curl -fs -u "admin:$PASSWORD" -H 'Accept: application/sparql-results+json' -G \
    --data-urlencode "query=ASK { <$probe> <urn:smoke:p> \"v\" }" "$BASE/$dataset/sparql")
  [[ "$answer" == *"\"boolean\" : $expected"* || "$answer" == *"\"boolean\":$expected"* ]]
}

dataset_exists() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" "$BASE/\$/datasets/$1")" = "200" ]
}

status_is() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' "${@:2}")" = "$1" ]
}

# Fuseki 6.2 labels the sample with application="fuseki" and prints it in scientific notation.
heap_max_bytes_is() {
  local actual
  actual=$(curl -fs -u "admin:$PASSWORD" "$BASE/\$/metrics" \
    | awk '/^jvm_memory_max_bytes/ && /area="heap"/ && /G1 Old Gen/ { printf "%.0f", $NF }')
  [ "$actual" = "$1" ]
}

upload_status() {
  head -c "$2" /dev/zero | tr '\0' 'a' | sed 's/^/<urn:s> <urn:p> "/; s/$/" ./' > "/tmp/$CONTAINER.nt"
  curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST \
    -H 'Content-Type: application/n-triples' --data-binary "@/tmp/$CONTAINER.nt" \
    "$BASE/$1/data?graph=$GRAPH"
}

exits_with_error() {
  local output
  if output=$(docker run --rm "$@" "$IMAGE" 2>&1); then
    return 1
  fi
  [[ "$output" == *ERROR* ]]
}

# The server process, not just the image default, must run without root. docker top lists the
# container's processes from outside, so the probe cannot match itself.
server_process_uid_is() {
  local actual
  actual=$(docker top "$CONTAINER" -eo pid,uid,args | awk '/fuseki-server\.jar/ { print $2; exit }')
  [ "$actual" = "$1" ]
}

refuses_volume_with() {
  local output
  if output=$(docker run --rm -v "$VOLUME:/fuseki" -e ADMIN_PASSWORD="$PASSWORD" "$IMAGE" 2>&1); then
    return 1
  fi
  [[ "$output" == *"$1"* ]]
}

volume_owner_is() {
  [ "$(docker run --rm -v "$VOLUME:/fuseki" --entrypoint stat "$IMAGE" -c '%u:%g %a' /fuseki)" = "$1" ]
}

reasoner() {
  docker exec "$CONTAINER" "$REASONER" "$@"
}

# Writes stdin to a file of a job directory, as the server's uid.
job_file() {
  docker exec -i "$CONTAINER" sh -c "mkdir -p '$JOBS/$1' && cat > '$JOBS/$1/$2'"
}

# Runs a step through the image's launcher; extra arguments are docker exec options.
run_step() {
  local job="$1" step="$2"
  shift 2
  docker exec "$@" "$CONTAINER" "$REASONER" run "$JOBS/$job/$step.json" > /dev/null 2>&1 || true
}

step_field() {
  docker exec "$CONTAINER" cat "$JOBS/$1/$2.status.json" | jq -r "$3"
}

step_ended() {
  [ "$(step_field "$1" "$2" .status)" = "$3" ] && [[ "$(step_field "$1" "$2" .reason)" == "$4"* ]]
}

classify_step() {
  printf '{"protocol": 1, "stepId": "%s", "kind": "CLASSIFY", "dataset": "working", "timeoutMillis": %s, "closure": {"root": "http://example.org/hard", "graphs": ["urn:hard"]}}' "$2" "$3" \
    | job_file "$1" "$2.json"
}

worker_pid() {
  docker exec "$CONTAINER" pgrep -f org.openmetadata.reasoner.Main
}

wait_for_worker() {
  for _ in $(seq 1 100); do
    worker_pid > /dev/null && return 0
    sleep 0.1
  done
  return 1
}

sparql_ask() {
  curl -fs -u "admin:$PASSWORD" -H 'Accept: application/sparql-results+json' -G \
    --data-urlencode "query=$1" "$BASE/ds/sparql" | jq -r .boolean
}

serving_triples() {
  curl -fs -u "admin:$PASSWORD" -H 'Accept: application/sparql-results+json' -G \
    --data-urlencode 'query=SELECT (COUNT(*) AS ?n) WHERE { GRAPH <urn:smoke:serving> { ?s ?p ?o } }' \
    "$BASE/ds/sparql" | jq -r '.results.bindings[0].n.value'
}

fuseki_pid() {
  docker top "$CONTAINER" -eo pid,args | awk '/FusekiServer/ { print $1; exit }'
}

# Fuseki is the same process, never OOM-killed, still serves the same data and still commits.
serving_intact() {
  local probe="urn:smoke:probe:$RANDOM"
  [ "$(fuseki_pid)" = "$SERVING_PID" ] \
    && [ "$(docker inspect -f '{{.State.OOMKilled}} {{.RestartCount}}' "$CONTAINER")" = "false 0" ] \
    && heap_max_bytes_is 536870912 \
    && [ "$(serving_triples)" = "1000" ] \
    && curl -fs -o /dev/null -u "admin:$PASSWORD" -X POST -H 'Content-Type: application/sparql-update' \
      --data "INSERT DATA { GRAPH <urn:smoke:probes> { <$probe> <urn:smoke:p> 1 } }" "$BASE/ds/update" \
    && [ "$(sparql_ask "ASK { GRAPH <urn:smoke:probes> { <$probe> ?p ?o } }")" = "true" ]
}

# Runs a step in the background and queries Fuseki every 200 ms until it ends: every query must
# be answered with the serving data unchanged.
answers_while_running() {
  run_step "$@" &
  local runner=$! answered=0 failed=0
  while kill -0 "$runner" 2> /dev/null; do
    if [ "$(serving_triples)" = "1000" ]; then
      answered=$((answered + 1))
    else
      failed=$((failed + 1))
    fi
    sleep 0.2
  done
  wait "$runner"
  echo "      $answered queries answered while the worker ran, $failed failed"
  [ "$failed" -eq 0 ] && [ "$answered" -gt 0 ]
}

conformance_reports_match() {
  local failed=0 step
  for step in "$@"; do
    if ! docker exec "$CONTAINER" cmp -s "$JOBS/conformance/${step%.*}.expected" \
      "$JOBS/conformance/$step.report.txt"; then
      echo "      report differs: $step"
      failed=1
    fi
  done
  [ "$failed" -eq 0 ]
}

routing_matches() {
  local fixture engine failed=0
  while read -r fixture engine; do
    if [ "$(step_field conformance "$fixture" .manifest.routing.engineUsed)" != "$engine" ]; then
      echo "      $fixture was not routed to $engine"
      failed=1
    fi
  done < <(grep -v '^#' "$FIXTURES/routing.expected")
  [ "$failed" -eq 0 ]
}

echo "== Defaults: stock Fuseki, extension off =="
start -e FUSEKI_DATASETS=ds
check "dataset seeded from FUSEKI_DATASETS" dataset_exists ds
check "a real dataset answers with Fuseki-Request-Id" has_request_id ds
check "no OpenMetadata headers without the extension" has_no_extension_headers ds
check "union default graph is off by default" union_is ds false
check "health ping is anonymous" status_is 200 "$BASE/\$/ping"
check "queries require the admin account" status_is 401 -G --data-urlencode 'query=ASK{}' "$BASE/ds/sparql"
check "heap follows FUSEKI_HEAP" heap_max_bytes_is 536870912
check "reasoning is off by default" [ "$(reasoner readiness 2> /dev/null | jq -r .status)" = "DISABLED" ]
check "no reasoning directory without reasoning" docker exec "$CONTAINER" test ! -e /fuseki/reasoning

echo "== Extension and performance settings from the environment =="
fresh_volume
start -e OPENMETADATA_EXTENSION_ENABLED=true -e FUSEKI_UNION_DEFAULT_GRAPH=true \
  -e FUSEKI_QUERY_TIMEOUT_MS=45000 -e FUSEKI_UPDATE_TIMEOUT_MS=46000 \
  -e OPENMETADATA_WRITE_TIMEOUT_MS=40000 -e OPENMETADATA_MAX_UPLOAD_BYTES=1048576 \
  -e FUSEKI_DATASETS=openmetadata,openmetadata_a,openmetadata_b
check "all alternates seeded" dataset_exists openmetadata_b
check "union header reflects FUSEKI_UNION_DEFAULT_GRAPH" header_equals openmetadata X-OpenMetadata-Union-Default-Graph true
check "query deadline reflects FUSEKI_QUERY_TIMEOUT_MS" header_equals openmetadata X-OpenMetadata-Query-Timeout-Ms 45000
check "update deadline reflects FUSEKI_UPDATE_TIMEOUT_MS" header_equals openmetadata X-OpenMetadata-Update-Timeout-Ms 46000
check "write deadline reflects OPENMETADATA_WRITE_TIMEOUT_MS" header_equals openmetadata X-OpenMetadata-Write-Timeout-Ms 40000
check "upload limit reflects OPENMETADATA_MAX_UPLOAD_BYTES" header_equals openmetadata X-OpenMetadata-Max-Upload-Bytes 1048576
check "union is in effect for queries" union_is openmetadata_a true
check "an upload under the limit is accepted" [ "$(upload_status openmetadata 1024)" = "201" ]
check "an upload over the limit is rejected with 413" [ "$(upload_status openmetadata 2097152)" = "413" ]

echo "== Turning the extension off again on the same volume =="
start -e FUSEKI_DATASETS=openmetadata
check "extension headers are gone" has_no_extension_headers openmetadata
check "the managed symlink was removed" docker exec "$CONTAINER" test ! -e /fuseki/extra/openmetadata-fuseki-extensions.jar

echo "== An existing dataset configuration is never modified =="
before=$(docker exec "$CONTAINER" sha256sum /fuseki/configuration/openmetadata.ttl)
start -e FUSEKI_DATASETS=openmetadata -e OPENMETADATA_EXTENSION_ENABLED=true -e FUSEKI_UNION_DEFAULT_GRAPH=true
check "configuration file unchanged" [ "$(docker exec "$CONTAINER" sha256sum /fuseki/configuration/openmetadata.ttl)" = "$before" ]
check "environment settings still apply to it" header_equals openmetadata X-OpenMetadata-Union-Default-Graph true

echo "== Runs without root =="
check "the server process runs as uid 1000" server_process_uid_is 1000
check "files it creates belong to uid 1000" [ "$(docker exec "$CONTAINER" stat -c %u /fuseki/databases/openmetadata)" = "1000" ]
fresh_volume
check "a new volume starts owned by 1000:0, group-writable" volume_owner_is "1000:0 775"

echo "== Upgrading a volume written by a root-running image =="
docker run --rm --user 0 --entrypoint sh -v "$VOLUME:/fuseki" "$IMAGE" -c \
  'mkdir -p /fuseki/databases/legacy && echo kept > /fuseki/databases/legacy/data \
   && chown -R 0:0 /fuseki && chmod -R 755 /fuseki'
check "a root-owned volume is refused with the fix" refuses_volume_with "fsGroupChangePolicy: OnRootMismatch"
docker run --rm --user 0 --entrypoint chown -v "$VOLUME:/fuseki" "$IMAGE" -R 1000:0 /fuseki
start -e FUSEKI_DATASETS=migrated
check "it starts after the documented chown" dataset_exists migrated
check "existing data survives the migration" [ "$(docker exec "$CONTAINER" cat /fuseki/databases/legacy/data)" = "kept" ]

echo "== Arbitrary uid in group 0 (OpenShift) =="
fresh_volume
start --user 123456:0 -e FUSEKI_DATASETS=arbitrary
check "a random uid in group 0 can create datasets" dataset_exists arbitrary

echo "== Invalid settings fail fast =="
check "missing ADMIN_PASSWORD" exits_with_error
check "non-numeric FUSEKI_QUERY_TIMEOUT_MS" exits_with_error -e ADMIN_PASSWORD=x -e FUSEKI_QUERY_TIMEOUT_MS=abc
check "non-boolean OPENMETADATA_EXTENSION_ENABLED" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_EXTENSION_ENABLED=yes
check "non-boolean OPENMETADATA_REASONING_ENABLED" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONING_ENABLED=yes
check "reasoning without the extension" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONING_ENABLED=true
check "non-size OPENMETADATA_REASONER_HEAP" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONER_HEAP=lots
check "OPENMETADATA_REASONER_HEAP under 16m" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONER_HEAP=8m
check "OPENMETADATA_REASONER_OFF_HEAP under its caps" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONER_OFF_HEAP=256m
check "invalid OPENMETADATA_REASONER_PAGE_CACHE_FLOOR" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONER_PAGE_CACHE_FLOOR=150%
check "non-numeric OPENMETADATA_REASONER_CPUS" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_REASONER_CPUS=two

echo "== Reasoner: readiness refuses an undersized container =="
fresh_volume
start --memory=1g "${REASONING[@]}" -e FUSEKI_DATASETS=ds
check "startup logs NOT_READY with the budget" bash -c "docker logs $CONTAINER 2>&1 | grep -q 'Reasoning NOT_READY: requires'"
readiness=$(reasoner readiness 2> /dev/null || true)
echo "      $(jq -c '{status, requiredBytes, availableBytes}' <<< "$readiness")"
check "readiness reports NOT_READY" [ "$(jq -r .status <<< "$readiness")" = "NOT_READY" ]
check "with required above available bytes" \
  [ "$(jq '.requiredBytes > .availableBytes and .availableBytes == 1073741824' <<< "$readiness")" = "true" ]
printf '{"protocol": 1, "stepId": "load", "kind": "LOAD", "dataset": "working", "timeoutMillis": 60000, "inputs": ["empty.nq"]}' \
  | job_file refused load.json
job_file refused empty.nq < /dev/null
run_step refused load
check "a step is refused NOT_READY" step_ended refused load NOT_READY "The container memory limit"
check "and no worker was started" docker exec "$CONTAINER" test ! -e "$JOBS/refused/load.log"
check "Fuseki serves as usual" status_is 200 -u "admin:$PASSWORD" -G --data-urlencode 'query=ASK{}' "$BASE/ds/sparql"

echo "== Reasoner: the conformance suite through the image =="
fresh_volume
start --memory=$MINIMUM_MEMORY "${REASONING[@]}" -e FUSEKI_DATASETS=ds
check "startup logs READY at the minimum memory" bash -c "docker logs $CONTAINER 2>&1 | grep -q 'Reasoning READY'"
check "readiness reports READY" [ "$(reasoner readiness 2> /dev/null | jq -r .status)" = "READY" ]
docker exec "$CONTAINER" mkdir -p "$JOBS/conformance"
docker cp "$FIXTURES/." "$CONTAINER:$JOBS/conformance/"
run_step conformance load
check "the fixtures load" step_ended conformance load SUCCEEDED LOADED
# Fixture names have no spaces; plain word splitting keeps this working with bash 3.
steps=($(cd "$FIXTURES" && ls -- *.json | sed 's/\.json$//' | grep -vx load))
files=("${steps[@]/#/$JOBS/conformance/}")
docker exec "$CONTAINER" "$REASONER" run "${files[@]/%/.json}" > /dev/null 2>&1 || true
check "all ${#steps[@]} fixtures match their expected reports" conformance_reports_match "${steps[@]}"
check "every answered fixture is routed to its expected engine" routing_matches
elk=($(awk '!/^#/ && $2 == "ELK" { print $1 }' "$FIXTURES/routing.expected"))
for fixture in "${elk[@]}"; do
  jq --arg id "$fixture.hermit" '.stepId = $id | .engine = "HERMIT" | .output = "urn:fixture:\($id):entailments"' \
    "$FIXTURES/$fixture.json" | job_file conformance "$fixture.hermit.json"
done
files=("${elk[@]/#/$JOBS/conformance/}")
docker exec "$CONTAINER" "$REASONER" run "${files[@]/%/.hermit.json}" > /dev/null 2>&1 || true
check "the ${#elk[@]} ELK-routed fixtures give identical reports through HermiT" \
  conformance_reports_match "${elk[@]/%/.hermit}"
docker exec "$CONTAINER" sh -c "cat $JOBS/conformance/*.status.json" \
  | jq -s -r 'map(select(.manifest.worker != null)) | "      worker heap, largest peak used: \(map(.manifest.worker.heapPeakUsedBytes) | max / 1048576 | floor) MiB, largest after GC: \(map(.manifest.worker.heapPeakAfterGcBytes) | max / 1048576 | floor) MiB, slowest step: \(map(.elapsedMillis) | max) ms"'

echo "== Reasoner: a worker never disturbs Fuseki =="
awk 'BEGIN { for (i = 0; i < 1000; i++) printf "<urn:smoke:s%d> <urn:smoke:p> \"%d\" .\n", i, i }' > "/tmp/$CONTAINER.serving.nt"
curl -fs -o /dev/null -u "admin:$PASSWORD" -X POST -H 'Content-Type: application/n-triples' \
  --data-binary "@/tmp/$CONTAINER.serving.nt" "$BASE/ds/data?graph=urn:smoke:serving"
SERVING_PID=$(fuseki_pid)
awk -v n=200 -f "$HERE/hard-ontology.awk" | job_file contain hard.trig
printf '{"protocol": 1, "stepId": "load", "kind": "LOAD", "dataset": "working", "timeoutMillis": 60000, "inputs": ["hard.trig"]}' \
  | job_file contain load.json
run_step contain load
check "the oversized closure loads" step_ended contain load SUCCEEDED LOADED

classify_step contain oom 600000
check "Fuseki answers while a 48m worker exhausts its heap" \
  answers_while_running contain oom -e OPENMETADATA_REASONER_HEAP=48m
check "the step reports INCOMPLETE for its memory budget" step_ended contain oom INCOMPLETE MEMORY_BUDGET
check "the worker exited with status 3" [ "$(step_field contain oom .exitCode)" = "3" ]
check "Fuseki is intact" serving_intact

classify_step contain timeout 5000
check "Fuseki answers while a classification runs out of time" answers_while_running contain timeout
check "the step reports INCOMPLETE for its time budget" step_ended contain timeout INCOMPLETE TIME_BUDGET
echo "      deadline to exit: $(step_field contain timeout .manifest.interruptLatencyMillis) ms, step $(step_field contain timeout .elapsedMillis) ms"
check "Fuseki is intact" serving_intact

classify_step contain wedged 3000
run_step contain wedged &
check "a worker starts" wait_for_worker
docker exec "$CONTAINER" kill -STOP "$(worker_pid)"
wait
check "a stopped worker is killed after its deadline" step_ended contain wedged INCOMPLETE "TIME_BUDGET: the worker did not exit"
echo "      SIGTERM to exit: $(step_field contain wedged .terminationLatencyMillis) ms, step $(step_field contain wedged .elapsedMillis) ms"
check "no worker is left" bash -c "! docker exec $CONTAINER pgrep -f org.openmetadata.reasoner.Main"
check "Fuseki is intact" serving_intact

awk 'BEGIN { for (i = 0; i < 1500000; i++) printf "<urn:smoke:s%d> <urn:smoke:p%d> \"value %d\" <urn:smoke:g%d> .\n", i, i % 50, i, i % 200 }' \
  | job_file kill big.nq
printf '{"protocol": 1, "stepId": "load", "kind": "LOAD", "dataset": "working", "timeoutMillis": 600000, "inputs": ["big.nq"]}' \
  | job_file kill load.json
run_step kill load &
check "a bulk load starts" wait_for_worker
sleep 3
docker exec "$CONTAINER" kill -KILL "$(worker_pid)"
wait
check "a worker killed during a write reports INTERRUPTED" step_ended kill load INTERRUPTED KILLED_BY_SIGNAL_9
check "and leaves no result manifest" docker exec "$CONTAINER" test ! -e "$JOBS/kill/load.result.json"
check "Fuseki is intact" serving_intact
rm -f "/tmp/$CONTAINER.serving.nt"

rm -f "/tmp/$CONTAINER.nt"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All checks passed"
