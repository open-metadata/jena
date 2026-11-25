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
# with a newer Apache Shiro release swapped into fuseki-server.jar (the server stage).
# The layout and environment contract match daschswiss/apache-jena-fuseki (stain/jena-docker
# lineage), so this image replaces it after a one-time ownership fix of the volume, because it
# runs as non-root: FUSEKI_BASE=/fuseki holds configuration/*.ttl, databases/ and shiro.ini.
# With no OpenMetadata variables set it runs stock Fuseki; the OpenMetadata Graph Store extension
# and the performance settings are opt-in environment variables, documented in README.md.

ARG FUSEKI_VERSION=6.3.0
# From https://downloads.apache.org/jena/binaries/apache-jena-fuseki-6.3.0.tar.gz.sha512
ARG FUSEKI_SHA512=18bb611a667d70842243b423fecd36c247e43d495d12889908e38711b57bea547d07641bd8b6dcacba6e40c019e6fce736adb3240e63799676bfba31c3e3bc7f

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
# Fuseki 6.3.0 bundles Apache Shiro 2.2.1 inside fuseki-server.jar, which scanners report as
# CVE-2026-56091. This stage swaps in the release pinned in server-jar/replacements.sha256 and
# leaves every other class of Apache's jar as released. Its bundled Micrometer 1.17.2 already has
# the fix for CVE-2026-59296, so Micrometer is no longer replaced. Remove the stage once a Fuseki
# release ships a fixed Shiro.
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

# Amazon Corretto 21's generic Linux build, from its official image: Temurin has not published
# OpenJDK's November 2026 security release yet. Only the jlinked runtime leaves this stage, not
# the image's Amazon Linux 2 base.
FROM amazoncorretto:21-al2-generic AS jre
# --strip-java-debug-attributes, not --strip-debug: Corretto ships its native libraries
# stripped, and --strip-debug would need objcopy for nothing.
RUN jlink \
      --add-modules ALL-MODULE-PATH \
      --strip-java-debug-attributes \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /javaruntime

FROM ubuntu:24.04
ARG FUSEKI_VERSION

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
# C.UTF-8 ships with glibc, so the JVM gets UTF-8 without the locales package.
ENV LANG=C.UTF-8
COPY --from=jre /javaruntime ${JAVA_HOME}

# gettext-base provides envsubst, used by the entrypoint to render shiro.ini and dataset configs.
# tzdata keeps TZ working for the entrypoint's log timestamps.
# The upgrade ships Ubuntu's current security fixes even when the base image is older than them.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends tini curl ca-certificates gettext-base tzdata \
    && rm -rf /var/lib/apt/lists/*

# CVE-2026-91403: eMudhra's compromised intermediates chain to three emSign roots (ECC G3, ECC C3,
# C1). Oracle JDK 21.0.13.0.3 also removes emSign G1, G2 and Comodo's AAA Certificate Services from
# cacerts. This drops them all from the JDK's cacerts by SHA-256 and fails the build if any is
# still trusted. Roots, not the advisory's intermediate fingerprints in blocked.certs: an ECDSA
# signature re-encoded as (r, n-s) gives an intermediate a new fingerprint that still verifies. Same
# step and reasoning as OpenMetadata's images (its ADR 2026-10-23, deleting roots). Corretto's
# cacerts has none of them, so nothing is deleted today; the step stays as the check.
RUN set -eu; \
    roots='86A1ECBA089C4A8D3BBE2734C612BA341D813E043CF9E8A862CD5C57A36BBE6B BC4D809B15189D78DB3E1D8CF4F9726A795DA1643CA5F1358E1DDB0EDC0D7EB3 125609AA301DA0A249B97A8239CB6A34216F44DCAC9F3954B14292F2E8C8608F 40F6AF0346A99AA1CD1D555A4E9CCE62C7F9634603EE406615833DC8C8D00367 1AA0C2709E831BD6E3B5129A00BA41F7EEEF020872F1E6504BF0F6C3F24F3AF3 D7A7A0FB5D7E2731D771E9484EBCDEF71D5F0C3E0A2948782BC83EE0EA699EF4'; \
    certs=$(keytool -list -v -cacerts -storepass changeit); \
    printf '%s\n' "$certs" \
      | awk -v roots="$roots" 'BEGIN { split(roots, r, " "); for (i in r) want[r[i]] = 1 } /^Alias name:/ { alias = substr($0, 13) } /SHA256:/ { gsub(":", "", $2); if ($2 in want) print alias }' \
      | while IFS= read -r alias; do keytool -delete -cacerts -storepass changeit -alias "$alias"; done; \
    certs=$(keytool -list -v -cacerts -storepass changeit); \
    if printf '%s\n' "$certs" | tr -d ':' | grep -qE "$(echo $roots | tr ' ' '|')"; then \
      echo "a root CA this step removes is still trusted" >&2; exit 1; \
    fi

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

COPY --from=server /jena-fuseki /jena-fuseki
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
