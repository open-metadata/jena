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

# Fuseki labels the sample with application="fuseki" and prints it in scientific notation.
server_flag_set() {
  docker exec "$CONTAINER" sh -c 'tr "\0" " " < /proc/$(pgrep -f fuseki-server.jar | head -1)/cmdline' \
    | grep -q -- "$1"
}

# Jetty takes an output buffer of the connector's configured size for every response with a body.
# When its pool cannot keep one that large, each response leaves direct memory that only a
# System.gc() frees, and G1 answers that with a stop-the-world full collection. 600 responses
# exceed the 256 MiB cap at any buffer size Fuseki has configured, so the count of such collections
# and the direct memory in use do not depend on which size it is.
responses_leave_direct_memory_alone() {
  local metrics direct system_gc
  for _ in $(seq 1 600); do
    curl -fs -o /dev/null -u "admin:$PASSWORD" -G --data-urlencode 'query=ASK{}' "$BASE/$1/sparql" \
      || return 1
  done
  metrics=$(curl -fs -u "admin:$PASSWORD" "$BASE/\$/metrics") || return 1
  grep -q '^jvm_gc_' <<< "$metrics" || return 1
  system_gc=$(grep -c 'cause="System.gc()"' <<< "$metrics" || true)
  direct=$(awk '/^jvm_buffer_memory_used_bytes/ && /id="direct"/ { printf "%.0f", $NF }' <<< "$metrics")
  echo "      after 600 queries: System.gc() series=$system_gc, direct buffers=${direct:-missing} bytes"
  [ "$system_gc" = "0" ] && [ -n "$direct" ] && [ "$direct" -lt 67108864 ]
}

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

# Fuseki 6.3.0 refuses an RDF Thrift row above 100,000 bytes. The extension lifts that to the upload
# limit, so a 150,000 character literal must round-trip: written as N-Triples, exported as Thrift by
# Fuseki itself (no binary fixture), uploaded as Thrift to another graph, and read back by length.
thrift_row_above_stock_cap_roundtrips() {
  local source="urn:smoke:thrift:source" target="urn:smoke:thrift:target" file="/tmp/$CONTAINER.trdf"
  head -c 150000 /dev/zero | tr '\0' 'a' | sed 's/^/<urn:s> <urn:p> "/; s/$/" ./' > "/tmp/$CONTAINER.nt"
  curl -fs -o /dev/null -u "admin:$PASSWORD" -X PUT -H 'Content-Type: application/n-triples' \
    --data-binary "@/tmp/$CONTAINER.nt" "$BASE/openmetadata/data?graph=$source" || return 1
  curl -fs -u "admin:$PASSWORD" -H 'Accept: application/rdf+thrift' -o "$file" \
    "$BASE/openmetadata/data?graph=$source" || return 1
  [[ "$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST \
    -H 'Content-Type: application/rdf+thrift' --data-binary "@$file" \
    "$BASE/openmetadata/data?graph=$target")" == 2* ]] || return 1
  [ "$(dataset_ask openmetadata "ASK { GRAPH <$target> { ?s ?p ?o FILTER(STRLEN(?o) = 150000) } }")" = "true" ]
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

# Status of a SPARQL update inserting $2 into $1's default graph.
update_status() {
  curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST \
    -H 'Content-Type: application/sparql-update' \
    --data "INSERT DATA { <$2> <urn:smoke:p> \"v\" }" "$BASE/$1"
}

update_applied() {
  [[ "$(update_status "$1" "$2")" == 2* ]]
}

storage_report() {
  curl -fs -u "admin:$PASSWORD" "$BASE/\$/openmetadata/storage"
}

dataset_ask() {
  curl -fs -u "admin:$PASSWORD" -H 'Accept: application/sparql-results+json' -G \
    --data-urlencode "query=$2" "$BASE/$1/sparql" | jq -r .boolean
}

# A file of the Fuseki JVM's /proc entry, seen from inside the container: docker top lists host
# pids. The [S] keeps the probe's own command line from matching.
fuseki_proc() {
  docker exec "$CONTAINER" sh -c "for p in /proc/[0-9]*; do
    if grep -q 'Fuseki[S]erver' \$p/cmdline 2> /dev/null; then tr '\0' ' ' < \$p/$1; break; fi
  done"
}

fuseki_jvm_has() {
  fuseki_proc cmdline | grep -q -- "$1"
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
check "direct buffers are capped" server_flag_set "-XX:MaxDirectMemorySize=256m"
check "responses neither force System.gc() nor pile up direct buffers" responses_leave_direct_memory_alone ds
check "the removed reasoning endpoint is not served" status_is 404 -u "admin:$PASSWORD" "$BASE/reasoning/capabilities"

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
check "an RDF Thrift row of 150,000 characters is stored whole" thrift_row_above_stock_cap_roundtrips

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

echo "== Disk space =="
fresh_volume
STORAGE=(-e FUSEKI_DATASETS=openmetadata -e OPENMETADATA_EXTENSION_ENABLED=true)
start "${STORAGE[@]}"
check "the storage report requires the admin account" status_is 401 "$BASE/\$/openmetadata/storage"
check "it reports the volume" [ "$(storage_report | jq '.totalBytes > 0 and .usableBytes > 0 and .reserveBytes > 0')" = "true" ]
check "writes are open above the reserve" [ "$(storage_report | jq .lowOnSpace)" = "false" ]
check "an update is applied above the reserve" update_applied openmetadata/update urn:smoke:kept
check "the JVM is told not to dump core" fuseki_jvm_has -XX:-CreateCoredumpOnCrash
start "${STORAGE[@]}" -e OPENMETADATA_STORAGE_MIN_FREE=999t
check "below the reserve the report says writes are refused" [ "$(storage_report | jq .lowOnSpace)" = "true" ]
check "an update gets 507 Insufficient Storage" [ "$(update_status openmetadata/update urn:smoke:refused)" = "507" ]
check "so does an update sent to the dataset URL" [ "$(update_status openmetadata urn:smoke:refused)" = "507" ]
check "and a Graph Store upload" [ "$(upload_status openmetadata 1024)" = "507" ]
check "nothing refused was applied" [ "$(dataset_ask openmetadata 'ASK { <urn:smoke:refused> ?p ?o }')" = "false" ]
check "queries still work" [ "$(dataset_ask openmetadata 'ASK { <urn:smoke:kept> ?p ?o }')" = "true" ]
check "compaction is not refused" status_is 200 -u "admin:$PASSWORD" -X POST "$BASE/\$/compact/openmetadata"

echo "== Invalid settings fail fast =="
check "missing ADMIN_PASSWORD" exits_with_error
check "non-numeric FUSEKI_QUERY_TIMEOUT_MS" exits_with_error -e ADMIN_PASSWORD=x -e FUSEKI_QUERY_TIMEOUT_MS=abc
check "non-boolean OPENMETADATA_EXTENSION_ENABLED" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_EXTENSION_ENABLED=yes
check "invalid OPENMETADATA_STORAGE_MIN_FREE" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_EXTENSION_ENABLED=true -e OPENMETADATA_STORAGE_MIN_FREE=lots

rm -f "/tmp/$CONTAINER.nt" "/tmp/$CONTAINER.trdf"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All checks passed"
