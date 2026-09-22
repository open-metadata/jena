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
# broken promise. Usage: test/smoke-test.sh <image>

set -euo pipefail

IMAGE="${1:?usage: smoke-test.sh <image>}"
PORT="${SMOKE_PORT:-3399}"
BASE="http://127.0.0.1:$PORT"
PASSWORD="smoke-admin"
GRAPH="https://open-metadata.org/graph/knowledge"
CONTAINER="fuseki-smoke-$$"
VOLUME="fuseki-smoke-$$"
FAILURES=0

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

echo "== Defaults: stock Fuseki, extension off =="
start -e FUSEKI_DATASETS=ds
check "dataset seeded from FUSEKI_DATASETS" dataset_exists ds
check "a real dataset answers with Fuseki-Request-Id" has_request_id ds
check "no OpenMetadata headers without the extension" has_no_extension_headers ds
check "union default graph is off by default" union_is ds false
check "health ping is anonymous" status_is 200 "$BASE/\$/ping"
check "queries require the admin account" status_is 401 -G --data-urlencode 'query=ASK{}' "$BASE/ds/sparql"
check "heap follows FUSEKI_HEAP" heap_max_bytes_is 536870912

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

echo "== Invalid settings fail fast =="
check "missing ADMIN_PASSWORD" exits_with_error
check "non-numeric FUSEKI_QUERY_TIMEOUT_MS" exits_with_error -e ADMIN_PASSWORD=x -e FUSEKI_QUERY_TIMEOUT_MS=abc
check "non-boolean OPENMETADATA_EXTENSION_ENABLED" exits_with_error -e ADMIN_PASSWORD=x -e OPENMETADATA_EXTENSION_ENABLED=yes

rm -f "/tmp/$CONTAINER.nt"
if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All checks passed"
