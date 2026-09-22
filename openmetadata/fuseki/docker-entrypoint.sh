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
# Prepares FUSEKI_BASE from environment variables, then runs the command (by default the Fuseki
# launcher). Every setting is opt-in: with none of the variables below set, apart from
# ADMIN_PASSWORD, the server behaves like stock Apache Jena Fuseki. See README.md.

set -euo pipefail

: "${FUSEKI_BASE:=/fuseki}"
: "${FUSEKI_HOME:=/jena-fuseki}"
: "${FUSEKI_MANAGE_SHIRO:=true}"
: "${FUSEKI_HEAP:=4g}"
: "${FUSEKI_UNION_DEFAULT_GRAPH:=false}"
: "${OPENMETADATA_EXTENSION_ENABLED:=false}"

EXTENSION_JAR="$FUSEKI_HOME/extensions/openmetadata-fuseki-extensions.jar"
EXTENSION_LINK="$FUSEKI_BASE/extra/openmetadata-fuseki-extensions.jar"
SERVER_CONFIG="${TMPDIR:-/tmp}/fuseki-server-context.ttl"

log() {
  printf '%s %-5s Entrypoint      :: %s\n' "$(date +%H:%M:%S)" "$1" "$2" >&2
}

fail() {
  log ERROR "$1"
  exit 1
}

require_boolean() {
  case "$2" in
    true | false) ;;
    *) fail "$1 must be true or false, got '$2'" ;;
  esac
}

require_positive_integer() {
  [[ "$2" =~ ^[1-9][0-9]*$ ]] || fail "$1 must be a positive integer, got '$2'"
}

# A volume written by an image that ran as root keeps its root-owned files: building this image
# cannot change data that already exists in a volume. Stop before Fuseki opens a database it
# cannot write. Only the volume root and data directories are checked, because configuration
# files are often read-only mounts, and symlinks are skipped because the extension link points
# into the read-only image.
first_unwritable_path() {
  local directory
  if [ ! -w "$FUSEKI_BASE" ]; then
    echo "$FUSEKI_BASE"
    return
  fi
  for directory in "$FUSEKI_BASE/databases" "$FUSEKI_BASE/lucene"; do
    if [ -d "$directory" ]; then
      find "$directory" ! -type l ! -writable -print -quit 2> /dev/null || true
    fi
  done
}

prepare_base() {
  local unwritable
  unwritable=$(first_unwritable_path)
  unwritable="${unwritable%%$'\n'*}"
  if [ -n "$unwritable" ]; then
    log ERROR "$unwritable is not writable by uid $(id -u). A volume written by an image that"
    log ERROR "ran as root needs a one-time ownership fix, with the container stopped:"
    log ERROR "  Docker:     docker run --rm --user 0 --entrypoint chown -v <volume>:/fuseki openmetadata/fuseki:${FUSEKI_VERSION:-<version>} -R $(id -u):0 /fuseki"
    log ERROR "  Kubernetes: securityContext.fsGroup: $(id -g) with fsGroupChangePolicy: OnRootMismatch"
    exit 1
  fi
  mkdir -p "$FUSEKI_BASE/configuration" "$FUSEKI_BASE/databases"
}

# Rendered on every start, so ADMIN_PASSWORD changes take effect. Set FUSEKI_MANAGE_SHIRO=false
# to keep an operator-provided $FUSEKI_BASE/shiro.ini instead.
render_shiro() {
  require_boolean FUSEKI_MANAGE_SHIRO "$FUSEKI_MANAGE_SHIRO"
  if [ "$FUSEKI_MANAGE_SHIRO" = "false" ]; then
    log INFO "Keeping operator-managed $FUSEKI_BASE/shiro.ini"
    return
  fi
  [ -n "${ADMIN_PASSWORD:-}" ] || fail "Set ADMIN_PASSWORD, or FUSEKI_MANAGE_SHIRO=false with your own shiro.ini"
  # Shiro's INI format separates the password from roles with a comma.
  [[ "$ADMIN_PASSWORD" != *","* && "$ADMIN_PASSWORD" != *$'\n'* ]] \
    || fail "ADMIN_PASSWORD must not contain a comma or a newline"
  # shellcheck disable=SC2016 # envsubst takes the variable names literally
  ADMIN_PASSWORD="$ADMIN_PASSWORD" envsubst '${ADMIN_PASSWORD}' \
    < "$FUSEKI_HOME/shiro.ini" > "$FUSEKI_BASE/shiro.ini"
}

# Existing configuration files are never modified, so seeding is safe on a populated volume.
seed_datasets() {
  local dataset
  local -a datasets
  IFS=',' read -r -a datasets <<< "${FUSEKI_DATASETS:-}"
  for dataset in "${datasets[@]}"; do
    dataset="${dataset//[[:space:]]/}"
    [ -n "$dataset" ] || continue
    [[ "$dataset" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail "Invalid dataset name '$dataset'"
    local config="$FUSEKI_BASE/configuration/$dataset.ttl"
    if [ -e "$config" ]; then
      log INFO "Dataset '$dataset' already configured in $config"
      continue
    fi
    # shellcheck disable=SC2016 # envsubst takes the variable names literally
    DATASET="$dataset" FUSEKI_BASE="$FUSEKI_BASE" envsubst '${DATASET} ${FUSEKI_BASE}' \
      < "$FUSEKI_HOME/dataset.ttl.template" > "$config"
    log INFO "Created dataset '$dataset' at $FUSEKI_BASE/databases/$dataset"
  done
}

# Only the extension's own file name is managed, so other jars in extra/ are left alone. A symlink
# always resolves to the jar shipped in the running image, so upgrades need no manual step.
configure_extension() {
  require_boolean OPENMETADATA_EXTENSION_ENABLED "$OPENMETADATA_EXTENSION_ENABLED"
  if [ "$OPENMETADATA_EXTENSION_ENABLED" = "true" ]; then
    mkdir -p "$FUSEKI_BASE/extra"
    ln -sfn "$EXTENSION_JAR" "$EXTENSION_LINK"
    log INFO "OpenMetadata Graph Store extension enabled"
  else
    rm -f "$EXTENSION_LINK"
  fi
}

compose_jvm_args() {
  if [ -z "${JVM_ARGS:-}" ]; then
    JVM_ARGS="-Xms$FUSEKI_HEAP -Xmx$FUSEKI_HEAP"
  fi
  require_boolean FUSEKI_UNION_DEFAULT_GRAPH "$FUSEKI_UNION_DEFAULT_GRAPH"
  if [ "$FUSEKI_UNION_DEFAULT_GRAPH" = "true" ]; then
    JVM_ARGS="$JVM_ARGS -Dtdb2:unionDefaultGraph=true"
  fi
  if [ "$OPENMETADATA_EXTENSION_ENABLED" = "true" ]; then
    append_positive_property OPENMETADATA_WRITE_TIMEOUT_MS openmetadata.fuseki.writeTimeoutMs
    append_positive_property OPENMETADATA_MAX_UPLOAD_BYTES openmetadata.fuseki.maxUploadBytes
  fi
  export JVM_ARGS
  log INFO "JVM_ARGS: $JVM_ARGS"
}

append_positive_property() {
  local value="${!1:-}"
  if [ -n "$value" ]; then
    require_positive_integer "$1" "$value"
    JVM_ARGS="$JVM_ARGS -D$2=$value"
  fi
}

# Fuseki reads query and update deadlines only from an assembler. Server-level context in a
# --config file applies to every dataset, including those in $FUSEKI_BASE/configuration.
render_server_context() {
  rm -f "$SERVER_CONFIG"
  local context=""
  add_context_entry FUSEKI_QUERY_TIMEOUT_MS arq:queryTimeout
  add_context_entry FUSEKI_UPDATE_TIMEOUT_MS arq:updateTimeout
  if [ -n "$context" ]; then
    {
      echo '@prefix fuseki: <http://jena.apache.org/fuseki#> .'
      echo '@prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .'
      echo '@prefix ja:     <http://jena.hpl.hp.com/2005/11/Assembler#> .'
      echo "[] rdf:type fuseki:Server$context ."
    } > "$SERVER_CONFIG"
  fi
}

# Appends to the caller's local "context" in the current shell, so a validation failure exits.
add_context_entry() {
  local value="${!1:-}"
  if [ -n "$value" ]; then
    require_positive_integer "$1" "$value"
    context+=$' ;\n   ja:context [ ja:cxtName "'"$2"'" ; ja:cxtValue "'"$value"'" ]'
  fi
}

has_config_argument() {
  local argument
  for argument in "$@"; do
    if [[ "$argument" == --config* ]]; then
      return 0
    fi
  done
  return 1
}

prepare_base
render_shiro
seed_datasets
configure_extension
compose_jvm_args
render_server_context

if [ -e "$SERVER_CONFIG" ] && [ "$(basename "${1:-}")" = "fuseki-server" ]; then
  if has_config_argument "$@"; then
    log WARN "A --config argument was given; FUSEKI_*_TIMEOUT_MS are ignored. Set ja:context there."
  else
    set -- "$@" "--config=$SERVER_CONFIG"
    log INFO "Server context from environment: $SERVER_CONFIG"
  fi
fi

exec "$@"
