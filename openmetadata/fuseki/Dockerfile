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
# Built from the released Apache Jena Fuseki distribution, verified against its published SHA-512.
# The layout and environment contract match daschswiss/apache-jena-fuseki (stain/jena-docker
# lineage), so this image replaces it after a one-time ownership fix of the volume, because it
# runs as non-root: FUSEKI_BASE=/fuseki holds configuration/*.ttl, databases/ and shiro.ini.
# With no OpenMetadata variables set it runs stock Fuseki; the OpenMetadata Graph Store extension
# and the performance settings are opt-in environment variables, documented in README.md.

ARG FUSEKI_VERSION=6.2.0
# From https://downloads.apache.org/jena/binaries/apache-jena-fuseki-6.2.0.tar.gz.sha512
ARG FUSEKI_SHA512=ba65f5867d2d4741b2ed9e2af5a0d4fbb447909894ab2a0c6bc4dac8997f4fe339c87b13c48d45d054977769f0f8bf763ea346b1f7792d5cdc458041bd43a132

FROM eclipse-temurin:21-jre-jammy AS distribution
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

FROM eclipse-temurin:21-jdk-jammy AS extension
WORKDIR /build
COPY --from=distribution /jena-fuseki/fuseki-server.jar /build/fuseki-server.jar
COPY extension/src/main/java /build/src
COPY extension/src/main/resources /build/resources
# Compiled against the exact server jar it will run with, so an incompatible Fuseki fails the build.
RUN mkdir classes \
    && javac --release 21 -proc:none -cp fuseki-server.jar -d classes \
        $(find src -name '*.java') \
    && jar --create --file openmetadata-fuseki-extensions.jar -C classes . -C resources .

FROM eclipse-temurin:21-jre-jammy
ARG FUSEKI_VERSION

# gettext-base provides envsubst, used by the entrypoint to render shiro.ini and dataset configs.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tini curl ca-certificates gettext-base \
    && rm -rf /var/lib/apt/lists/*

LABEL org.opencontainers.image.title="Apache Jena Fuseki packaged by OpenMetadata"
LABEL org.opencontainers.image.description="Apache Jena Fuseki ${FUSEKI_VERSION} with an optional OpenMetadata Graph Store extension. Not an Apache Software Foundation release."
LABEL org.opencontainers.image.version="${FUSEKI_VERSION}"
LABEL org.opencontainers.image.source="https://github.com/open-metadata/jena"
LABEL org.opencontainers.image.documentation="https://github.com/open-metadata/jena/tree/main/openmetadata/fuseki"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.vendor="OpenMetadata"

ENV FUSEKI_VERSION=${FUSEKI_VERSION} \
    FUSEKI_HOME=/jena-fuseki \
    FUSEKI_BASE=/fuseki

COPY --from=distribution /jena-fuseki /jena-fuseki
COPY --from=extension /build/openmetadata-fuseki-extensions.jar \
    /jena-fuseki/extensions/openmetadata-fuseki-extensions.jar
COPY shiro.ini /jena-fuseki/shiro.ini
COPY dataset.ttl.template /jena-fuseki/dataset.ttl.template
COPY docker-entrypoint.sh healthcheck.sh /
RUN chmod 755 /docker-entrypoint.sh /healthcheck.sh

# The server runs as this non-root uid, matching upstream Jena's Docker kit. A new named volume
# copies the ownership below; group 0 with group write keeps it usable under OpenShift, which
# runs containers as an arbitrary uid in group 0. Volumes written by a root-running image need
# the one-time ownership fix in README.md.
ARG FUSEKI_UID=1000
RUN groupadd --gid "${FUSEKI_UID}" fuseki \
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
