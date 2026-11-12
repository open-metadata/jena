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
# Replaces the Apache Shiro and Micrometer classes that fuseki-server.jar bundles with the releases
# in replacements.sha256, each downloaded from Maven Central and checked against its SHA-256.
# Fuseki keeps both libraries under their own package names, so swapping them needs no relocation.
# Usage: replace-libraries.sh <fuseki-server.jar> <replacements.sha256>. Needs curl, zip and unzip.
#
# Fuseki's merged META-INF/services files are kept: the new releases register the same providers,
# which the last check below confirms.

set -euo pipefail

JAR="$(realpath "$1")"
LIST="$(realpath "$2")"
MAVEN_CENTRAL=https://repo1.maven.org/maven2
# Everything the replaced libraries contribute to the jar, and nothing else.
REPLACED=('org/apache/shiro/*' 'io/micrometer/*' 'META-INF/maven/org.apache.shiro/*'
  'META-INF/maven/org.apache.shiro.crypto/*' 'META-INF/micrometer-*'
  'META-INF/native-image/io.micrometer/*' 'META-INF/shiro.tld')

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/jars" "$work/classes"

while read -r checksum path; do
  name="$(basename "$path")"
  curl --fail --location --silent --show-error --retry 3 --output "$work/jars/$name" \
    "$MAVEN_CENTRAL/$path"
  echo "$checksum  $work/jars/$name" | sha256sum --strict --check --quiet -
done < "$LIST"

zip --quiet --delete "$JAR" "${REPLACED[@]}"
if unzip -Z1 "$JAR" "${REPLACED[@]}" > /dev/null 2>&1; then
  echo "fuseki-server.jar still holds files of a replaced library" >&2
  exit 1
fi

for library in "$work"/jars/*.jar; do
  unzip -q -o "$library" -d "$work/classes" -x 'META-INF/MANIFEST.MF' 'META-INF/LICENSE' \
    'META-INF/NOTICE' 'META-INF/DEPENDENCIES' 'META-INF/services/*'
done
(cd "$work/classes" && zip --quiet --recurse-paths -X "$JAR" .)

for service in org.apache.shiro.crypto.hash.HashSpi io.micrometer.context.ThreadLocalAccessor; do
  for provider in $(unzip -p "$JAR" "META-INF/services/$service" | grep -v '^#'); do
    if ! unzip -Z1 "$JAR" "${provider//.//}.class" > /dev/null 2>&1; then
      echo "META-INF/services/$service names $provider, which the jar no longer has" >&2
      exit 1
    fi
  done
done
