# Apache Jena Fuseki packaged by OpenMetadata

A Docker image of the released Apache Jena Fuseki distribution with an optional OpenMetadata Graph
Store extension. **This is not an Apache Software Foundation release**; report Fuseki issues to
[apache/jena](https://github.com/apache/jena).

The layout and environment contract match `daschswiss/apache-jena-fuseki` (the
[stain/jena-docker](https://github.com/stain/jena-docker) lineage), so this image replaces it: the same
volume, dataset files and `ADMIN_PASSWORD` keep working after a one-time ownership fix, because this
image runs as non-root (see [Running as non-root](#running-as-non-root)).

With only `ADMIN_PASSWORD` set, the server behaves like stock Fuseki. Everything else, including the
extension, is an opt-in environment variable.

## Quick start

```bash
docker run -d -p 3030:3030 -v fuseki:/fuseki \
  -e ADMIN_PASSWORD=change-me \
  -e FUSEKI_DATASETS=ds \
  openmetadata/fuseki:6.2.0
```

## Environment variables

| Variable | Default | Effect |
|---|---|---|
| `ADMIN_PASSWORD` | required | Password of the `admin` account in the rendered `shiro.ini`. Must not contain a comma. |
| `FUSEKI_MANAGE_SHIRO` | `true` | `false` keeps an operator-provided `$FUSEKI_BASE/shiro.ini`; `ADMIN_PASSWORD` is then not required. |
| `FUSEKI_DATASETS` | unset | Comma-separated dataset names. Each gets a TDB2 dataset config in `$FUSEKI_BASE/configuration/<name>.ttl` if that file does not exist. Existing files are never modified. |
| `FUSEKI_HEAP` | `4g` | Sets both `-Xms` and `-Xmx`, so the page-cache budget is predictable. Ignored when `JVM_ARGS` is set. |
| `JVM_ARGS` | unset | Replaces the heap flags entirely (as in the DaSCH image). The settings below are still appended. |
| `FUSEKI_QUERY_TIMEOUT_MS` | unset | Server-wide `arq:queryTimeout`, applied to every dataset including existing ones. |
| `FUSEKI_UPDATE_TIMEOUT_MS` | unset | Server-wide `arq:updateTimeout`, applied to every dataset. |
| `FUSEKI_UNION_DEFAULT_GRAPH` | `false` | `true` makes the default graph the union of named graphs for every TDB2 dataset. |
| `OPENMETADATA_EXTENSION_ENABLED` | `false` | `true` loads the OpenMetadata Graph Store extension. |
| `OPENMETADATA_WRITE_TIMEOUT_MS` | `50000` | Extension only: deadline for a Graph Store upload, after which it is rolled back. |
| `OPENMETADATA_MAX_UPLOAD_BYTES` | `67108864` | Extension only: largest accepted upload; larger ones get HTTP 413. |

Invalid values stop the container at start with an `ERROR` line naming the variable. Numeric values
must be positive integers; booleans must be `true` or `false`.

Timeouts are server-wide because Fuseki reads them only from an assembler: the entrypoint renders a
small `--config` file containing a server-level `ja:context`, which applies to every dataset in
`$FUSEKI_BASE/configuration`. If you pass your own `--config`, set `ja:context` there instead; the
entrypoint warns and does not add its own.

## What the extension adds

Stock Fuseki holds TDB2's single writer lock while a Graph Store upload body transfers. On Fuseki 6.0.0
with identical configuration, a small update issued during a 4.4 MB upload at 200 KB/s waited 20 s
without the extension and 0.23 s with it. The extension receives the whole upload before taking the
lock, caps its size, and rolls it back if it passes the deadline. `arq:updateTimeout` does not cover
Graph Store uploads.

It also advertises the dataset's guarantees on `OPTIONS /<dataset>/data` (`X-OpenMetadata-*` headers),
which OpenMetadata reads before indexing. OpenMetadata works without the extension and logs which
guarantees are missing.

When `OPENMETADATA_EXTENSION_ENABLED` is not `true`, the server runs with exactly the stock launch
command: the extension's classes are never loaded.

## Settings for OpenMetadata

```bash
ADMIN_PASSWORD=<secret>
FUSEKI_DATASETS=openmetadata,openmetadata_a,openmetadata_b
FUSEKI_UNION_DEFAULT_GRAPH=true
FUSEKI_QUERY_TIMEOUT_MS=50000
FUSEKI_UPDATE_TIMEOUT_MS=50000
OPENMETADATA_EXTENSION_ENABLED=true
FUSEKI_HEAP=4g
```

The `_a` and `_b` datasets are the blue/green rebuild alternates; OpenMetadata derives their names from
the dataset in `RDF_ENDPOINT`, so a deployment serving `collate` uses
`collate,collate_a,collate_b`. Point OpenMetadata at the admin account:
`RDF_ENDPOINT=http://fuseki:3030/openmetadata`, `RDF_REMOTE_USERNAME=admin`.

The timeouts deliberately sit below OpenMetadata's 60-second client deadline, so Fuseki abandons the
work and releases the writer before the client gives up.

## Layout

| Path | Contents |
|---|---|
| `/jena-fuseki` | The Fuseki distribution (`FUSEKI_HOME`), plus `extensions/` |
| `/fuseki` | Volume (`FUSEKI_BASE`): `configuration/`, `databases/`, `shiro.ini`, `extra/` |

The extension is enabled by a symlink `/fuseki/extra/openmetadata-fuseki-extensions.jar` pointing at the
jar in the image, so it always matches the running image. Other jars in `extra/` are left alone.

## Running as non-root

The server runs as uid 1000 (`USER 1000:1000`, numeric so Kubernetes can verify `runAsNonRoot`),
matching upstream Jena's Docker kit. `/fuseki` in the image is owned by `1000:0` and group-writable,
and a new named volume copies that ownership, so a fresh volume needs no setup. Group 0 write access
also covers OpenShift, which runs containers as an arbitrary uid in group 0. Build with
`--build-arg FUSEKI_UID=<uid>` to change the uid.

The image cannot change ownership of data that already exists in a volume. At start, the entrypoint
checks that the volume root and its data directories are writable, and if not, stops with the fix
instead of letting Fuseki fail on its first write. Bind mounts behave like existing volumes: make
the host directory writable by uid 1000.

**Kubernetes** fixes ownership when it mounts the volume:

```yaml
securityContext:
  runAsNonRoot: true
  fsGroup: 1000
  fsGroupChangePolicy: OnRootMismatch   # only walks the volume while its root does not match
```

**Docker**, once, with the container stopped:

```bash
docker run --rm --user 0 --entrypoint chown -v <volume>:/fuseki openmetadata/fuseki:6.2.0 -R 1000:0 /fuseki
```

Starting as root to fix ownership and then dropping privileges would avoid that step, but leaves
root as the image's declared user, which image scanners flag and restricted Kubernetes pods
reject unless every pod sets `runAsUser`.

## Replacing daschswiss/apache-jena-fuseki

The DaSCH image runs as root, so its volumes are root-owned. Apply the ownership fix above once, then
swap the image and keep the volume. Existing `configuration/*.ttl` files are loaded unchanged, and
the environment settings above apply to them without editing, including union and timeouts. DaSCH's
built-in `dsp-repo` dataset is not created by this image.

## Build and test

```bash
cd openmetadata/fuseki
(cd extension && mvn -B verify)
docker build -t openmetadata-fuseki:local .
./test/smoke-test.sh openmetadata-fuseki:local
```

The image build downloads the release from Apache's CDN, falling back to the archive, and checks its
published SHA-512. The extension is compiled against the exact `fuseki-server.jar` it runs with, so an
incompatible Fuseki fails the build.

## Publishing

Images are published only from `release/<fuseki-version>` branches, never from `main`. A release branch
is cut from the matching upstream tag (`jena-6.2.0` for `release/6.2.0`), and its name must match the
`FUSEKI_VERSION` in the Dockerfile.

Each publish pushes two tags for `linux/amd64` and `linux/arm64`: `<version>`, which moves to the latest
build of that release, and the immutable `<version>-<commit>`. Pin the immutable tag in tests.
