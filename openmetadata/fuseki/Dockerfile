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

# Apache Jena Fuseki packaged by OpenMetadata. This is not an Apache Software Foundation release.
#
# Built from the released Apache Jena Fuseki distribution, verified against its published SHA-512,
# with newer Apache Shiro and Micrometer releases swapped into fuseki-server.jar (the server stage).
# The layout and environment contract match daschswiss/apache-jena-fuseki (stain/jena-docker
# lineage), so this image replaces it after a one-time ownership fix of the volume, because it
# runs as non-root: FUSEKI_BASE=/fuseki holds configuration/*.ttl, databases/ and shiro.ini.
# With no OpenMetadata variables set it runs stock Fuseki; the OpenMetadata Graph Store extension,
# the reasoner and the performance settings are opt-in environment variables, documented in
# README.md.

ARG FUSEKI_VERSION=6.2.0
# From https://downloads.apache.org/jena/binaries/apache-jena-fuseki-6.2.0.tar.gz.sha512
ARG FUSEKI_SHA512=ba65f5867d2d4741b2ed9e2af5a0d4fbb447909894ab2a0c6bc4dac8997f4fe339c87b13c48d45d054977769f0f8bf763ea346b1f7792d5cdc458041bd43a132
# OpenMetadata's HermiT build (LGPL-3.0), built from source at this commit of
# https://github.com/open-metadata/hermit-reasoner, which pull request #1 merged into master
# (0e2dbb2f). Moves to the released artifact, verified by checksum, once that build is released.
ARG HERMIT_REPOSITORY=https://github.com/open-metadata/hermit-reasoner.git
ARG HERMIT_COMMIT=bb78447a0563958eb52409e10a592022c14ff934
ARG HERMIT_VERSION=1.4.6.519-om.1

FROM eclipse-temurin:21-jre-noble AS distribution
ARG FUSEKI_VERSION
ARG FUSEKI_SHA512
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /tmp
# The CDN serves only current releases; the archive keeps every release, so it is the fallback.
RUN set -eux; \
    tarball="apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz"; \
    curl --fail --location --silent --show-error --retry 3 --output "$tarball" \
        "https://dlcdn.apache.org/jena/binaries/$tarball" \
    || curl --fail --location --silent --show-error --retry 3 --output "$tarball" \
        "https://archive.apache.org/dist/jena/binaries/$tarball"; \
    echo "${FUSEKI_SHA512}  $tarball" | sha512sum -c -; \
    tar -xzf "$tarball"; \
    mv "apache-jena-fuseki-${FUSEKI_VERSION}" /jena-fuseki; \
    rm -f /jena-fuseki/fuseki.war
RUN /jena-fuseki/fuseki-server --version 2>&1 | grep -qx "Apache Jena version ${FUSEKI_VERSION}"

# Jars are architecture-independent, so builder stages run natively on the build host.
# Fuseki 6.2.0 bundles Apache Shiro 2.2.1 and Micrometer 1.17.0 inside fuseki-server.jar, which
# scanners report as CVE-2026-56091 and CVE-2026-59296. This stage swaps in the releases pinned in
# server-jar/replacements.sha256 and leaves every other class of Apache's jar as released. Remove
# it once a Fuseki release ships fixed versions of both.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS server
ARG FUSEKI_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates zip unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=distribution /jena-fuseki /jena-fuseki
COPY server-jar /build/server-jar
RUN /build/server-jar/replace-libraries.sh /jena-fuseki/fuseki-server.jar \
        /build/server-jar/replacements.sha256 \
    && /jena-fuseki/fuseki-server --version 2>&1 | grep -qx "Apache Jena version ${FUSEKI_VERSION}"

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS extension
WORKDIR /build
COPY --from=server /jena-fuseki/fuseki-server.jar /build/fuseki-server.jar
COPY extension/src/main/java /build/src
COPY extension/src/main/resources /build/resources
# Compiled against the exact server jar it will run with, so an incompatible Fuseki fails the build.
RUN mkdir classes \
    && javac --release 21 -proc:none -cp fuseki-server.jar -d classes \
        $(find src -name '*.java') \
    && jar --create --file openmetadata-fuseki-extensions.jar -C classes . -C resources .

# The reasoner worker and its dependencies, run as a separate JVM with the classpath
# /jena-fuseki/fuseki-server.jar:/jena-fuseki/reasoner/*. Jena is provided by fuseki-server.jar;
# the build fails if the jar Maven compiled against is not byte for byte Apache's released jar,
# whose Jena classes the image ships unchanged, or if any third-party jar differs from
# dependencies.sha256.
# Pinned by digest, so a rebuild of the same commit uses the same Maven and JDK.
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS reasoner
ARG FUSEKI_VERSION
ARG HERMIT_REPOSITORY
ARG HERMIT_COMMIT
ARG HERMIT_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN set -eux; \
    git init --quiet hermit; \
    git -C hermit fetch --quiet --depth 1 "$HERMIT_REPOSITORY" "$HERMIT_COMMIT"; \
    git -C hermit checkout --quiet --detach FETCH_HEAD; \
    test "$(git -C hermit rev-parse HEAD)" = "$HERMIT_COMMIT"; \
    cd hermit; \
    mvn -B -ntp -Dmaven.test.skip=true -Dmaven.javadoc.skip=true install; \
    test -f "target/hermit-reasoner-${HERMIT_VERSION}-sources.jar"
COPY reasoner/pom.xml /build/reasoner/pom.xml
RUN cd reasoner && mvn -B -ntp dependency:go-offline
COPY reasoner/src/main /build/reasoner/src/main
COPY reasoner/dependencies.sha256 reasoner/NOTICE reasoner/THIRD-PARTY.md /build/reasoner/
COPY reasoner/licenses /build/reasoner/licenses
COPY reasoner/bin /build/reasoner/bin
COPY --from=distribution /jena-fuseki/fuseki-server.jar /build/fuseki-server.jar
RUN set -eux; \
    cd reasoner; \
    mvn -B -ntp -Dmaven.test.skip=true package; \
    cmp /build/fuseki-server.jar \
        "/root/.m2/repository/org/apache/jena/jena-fuseki-server/${FUSEKI_VERSION}/jena-fuseki-server-${FUSEKI_VERSION}.jar"; \
    (cd target/reasoner && sha256sum --strict --check /build/reasoner/dependencies.sha256); \
    expected=$(($(wc -l < dependencies.sha256) + 2)); \
    test "$(ls target/reasoner/*.jar | wc -l)" -eq "$expected"; \
    mkdir -p /out/reasoner/source /out/reasoner/bin; \
    cp target/reasoner/*.jar /out/reasoner/; \
    cp "/build/hermit/target/hermit-reasoner-${HERMIT_VERSION}-sources.jar" /out/reasoner/source/; \
    cp -r NOTICE THIRD-PARTY.md licenses /out/reasoner/; \
    cp bin/reasoner /out/reasoner/bin/reasoner

FROM eclipse-temurin:21-jre-noble
ARG FUSEKI_VERSION

# gettext-base provides envsubst, used by the entrypoint to render shiro.ini and dataset configs.
# The upgrade ships Ubuntu's current security fixes even when the base image is older than them.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends tini curl ca-certificates gettext-base \
    && rm -rf /var/lib/apt/lists/*

# CVE-2026-91403: eMudhra's compromised intermediates chain to three emSign roots (ECC G3, ECC C3,
# C1), which the base's Temurin 21.0.12.1 still trusts. This drops them from the JDK's cacerts by
# SHA-256 and fails the build if any is still trusted; emSign Root CA - G1 is not affected and
# stays. Roots, not the advisory's intermediate fingerprints in blocked.certs: an ECDSA signature
# re-encoded as (r, n-s) gives an intermediate a new fingerprint that still verifies. Same step and
# reasoning as OpenMetadata's images (its ADR 2026-10-23, deleting roots). Remove it once
# eclipse-temurin:21-jre-noble is on Temurin 21.0.13 or newer: that release drops the roots itself
# and this step deletes nothing. The ENTRYPOINT below replaces Temurin's, so its
# /__cacert_entrypoint.sh never re-imports the system CA store into cacerts.
RUN set -eu; \
    roots='86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B BC4D809B15189D78DB3E1D8CF4F9726A795DA1643CA5F1358E1DDB0EDC0D7EB3 125609AA301DA0A249B97A8239CB6A34216F44DCAC9F3954B14292F2E8C8608F'; \
    certs=$(keytool -list -v -cacerts -storepass changeit); \
    printf '%s\n' "$certs" \
      | awk -v roots="$roots" 'BEGIN { split(roots, r, " "); for (i in r) want[r[i]] = 1 } /^Alias name:/ { alias = substr($0, 13) } /SHA256:/ { gsub(":", "", $2); if ($2 in want) print alias }' \
      | while IFS= read -r alias; do keytool -delete -cacerts -storepass changeit -alias "$alias"; done; \
    certs=$(keytool -list -v -cacerts -storepass changeit); \
    if printf '%s\n' "$certs" | tr -d ':' | grep -qE "$(echo $roots | tr ' ' '|')"; then \
      echo "CVE-2026-91403: an emSign root is still trusted" >&2; exit 1; \
    fi

LABEL org.opencontainers.image.title="Apache Jena Fuseki packaged by OpenMetadata"
LABEL org.opencontainers.image.description="Apache Jena Fuseki ${FUSEKI_VERSION} with an optional OpenMetadata Graph Store extension and reasoner worker. Not an Apache Software Foundation release."
LABEL org.opencontainers.image.version="${FUSEKI_VERSION}"
LABEL org.opencontainers.image.source="https://github.com/open-metadata/jena"
LABEL org.opencontainers.image.documentation="https://github.com/open-metadata/jena/tree/main/openmetadata/fuseki"
# The reasoner worker in /jena-fuseki/reasoner bundles HermiT (LGPL-3.0-or-later); see its
# THIRD-PARTY.md.
LABEL org.opencontainers.image.licenses="Apache-2.0 AND LGPL-3.0-or-later"
LABEL org.opencontainers.image.vendor="OpenMetadata"

ENV FUSEKI_VERSION=${FUSEKI_VERSION} \
    FUSEKI_HOME=/jena-fuseki \
    FUSEKI_BASE=/fuseki

COPY --from=server /jena-fuseki /jena-fuseki
COPY --from=extension /build/openmetadata-fuseki-extensions.jar \
    /jena-fuseki/extensions/openmetadata-fuseki-extensions.jar
# Read-only in the image, never on the volume: an upgrade can never leave stale worker jars, and
# no reasoner dependency is ever loaded by the Fuseki JVM.
COPY --from=reasoner /out/reasoner /jena-fuseki/reasoner
COPY shiro.ini /jena-fuseki/shiro.ini
COPY dataset.ttl.template /jena-fuseki/dataset.ttl.template
COPY docker-entrypoint.sh healthcheck.sh /
RUN chmod 755 /docker-entrypoint.sh /healthcheck.sh

# The server runs as this non-root uid, matching upstream Jena's Docker kit. A new named volume
# copies the ownership below; group 0 with group write keeps it usable under OpenShift, which
# runs containers as an arbitrary uid in group 0. Volumes written by a root-running image need
# the one-time ownership fix in README.md.
# Ubuntu 24.04 images ship an "ubuntu" account with uid and gid 1000, so it is removed first.
ARG FUSEKI_UID=1000
RUN if id ubuntu > /dev/null 2>&1; then userdel --remove ubuntu; fi \
    && groupadd --gid "${FUSEKI_UID}" fuseki \
    && useradd --uid "${FUSEKI_UID}" --gid fuseki --home-dir /fuseki --no-create-home \
        --shell /usr/sbin/nologin fuseki \
    && mkdir -p /fuseki \
    && chown "${FUSEKI_UID}:0" /fuseki \
    && chmod 0775 /fuseki

VOLUME /fuseki
WORKDIR /jena-fuseki
EXPOSE 3030

HEALTHCHECK --interval=15s --timeout=3s --retries=3 --start-period=30s CMD /healthcheck.sh

# Numeric, so Kubernetes can verify runAsNonRoot without a pod-level runAsUser.
USER ${FUSEKI_UID}:${FUSEKI_UID}
ENTRYPOINT ["/usr/bin/tini", "--", "/docker-entrypoint.sh"]
CMD ["/jena-fuseki/fuseki-server"]
