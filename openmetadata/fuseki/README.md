# Apache Jena Fuseki packaged by OpenMetadata

A Docker image of the released Apache Jena Fuseki distribution with an optional OpenMetadata Graph
Store extension. **This is not an Apache Software Foundation
release**; report Fuseki issues to [apache/jena](https://github.com/apache/jena).

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
  openmetadata/fuseki:6.3.0
```

## Environment variables

| Variable | Default | Effect |
|---|---|---|
| `ADMIN_PASSWORD` | required | Password of the `admin` account in the rendered `shiro.ini`. Must not contain a comma. |
| `FUSEKI_MANAGE_SHIRO` | `true` | `false` keeps an operator-provided `$FUSEKI_BASE/shiro.ini`; `ADMIN_PASSWORD` is then not required. |
| `FUSEKI_DATASETS` | unset | Comma-separated dataset names. Each gets a TDB2 dataset config in `$FUSEKI_BASE/configuration/<name>.ttl` if that file does not exist. Existing files are never modified. |
| `FUSEKI_HEAP` | `4g` | Sets both `-Xms` and `-Xmx`, so the page-cache budget is predictable. Ignored when `JVM_ARGS` is set. |
| `JVM_ARGS` | unset | Replaces the heap flags entirely (as in the DaSCH image). The settings below are still appended, and so is `-XX:MaxDirectMemorySize=256m` unless `JVM_ARGS` sets that flag. |
| `FUSEKI_QUERY_TIMEOUT_MS` | unset | Server-wide `arq:queryTimeout`, applied to every dataset including existing ones. |
| `FUSEKI_UPDATE_TIMEOUT_MS` | unset | Server-wide `arq:updateTimeout`, applied to every dataset. |
| `FUSEKI_UNION_DEFAULT_GRAPH` | `false` | `true` makes the default graph the union of named graphs for every TDB2 dataset. |
| `OPENMETADATA_EXTENSION_ENABLED` | `false` | `true` loads the OpenMetadata Graph Store extension. |
| `OPENMETADATA_WRITE_TIMEOUT_MS` | `50000` | Extension only: deadline for a Graph Store upload, after which it is rolled back. |
| `OPENMETADATA_MAX_UPLOAD_BYTES` | `67108864` | Extension only: largest accepted upload; larger ones get HTTP 413. |
| `OPENMETADATA_STORAGE_MIN_FREE` | `10%` | Extension only: free space the data volume keeps. Below it every write gets HTTP 507 Insufficient Storage, nothing is applied, and reads and compaction keep working. A size such as `20g`, or a percentage of the volume. See [Disk space](#disk-space). |
| `OPENMETADATA_STORAGE_ROOT` | `$FUSEKI_BASE/databases` | Extension only: the directory holding the TDB2 datasets, which the write guard and the storage report measure. |

Invalid values stop the container at start with an `ERROR` line naming the variable. Numeric values
must be positive integers; sizes use the JVM's notation (`512m`, `2g`) with at most 13 digits;
booleans must be `true` or `false`. An empty value counts as unset.

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

It also lifts a limit that Jena 6.3.0 added to RDF Thrift. `TRDF` hard-codes 100,000 bytes per triple or
quad row (GH-4377), so stock Fuseki answers 500 `Unrecognized: RDF_StreamRow` to an
`application/rdf+thrift` upload with one row above that, and stores nothing. OpenMetadata uploads
bulk RDF this way, so one entity with a description or SQL over 100 KB failed a whole batch. With the
extension, Graph Store POST and PUT read Thrift with a row cap equal to `OPENMETADATA_MAX_UPLOAD_BYTES`
(`ThriftRows`, called from `BoundedGraphStore`). That bound already limits what one request can
allocate, so a lower row cap protects nothing more. A body above it still gets 413. Other Thrift
readers in the server, such as SPARQL `LOAD`, keep Jena's cap.

It also advertises the dataset's guarantees on `OPTIONS /<dataset>/data` (`X-OpenMetadata-*` headers),
which OpenMetadata reads before indexing. OpenMetadata works without the extension and logs which
guarantees are missing.

When `OPENMETADATA_EXTENSION_ENABLED` is not `true`, the server runs with exactly the stock launch
command: the extension's classes are never loaded.

### Disk space

TDB2 never reuses space on its own: every write transaction copies the index pages it touches, and the
old pages stay on disk until the dataset is compacted (`POST /$/compact/<dataset>?deleteOld=true`).
A dataset that only receives updates therefore grows until its volume is full. TDB2 memory-maps its
files, so a write on a full volume does not fail cleanly: the JVM gets SIGBUS and exits, and keeps
doing so on every restart. Compaction also needs free space about the size of the live data.

The extension protects both:

- **A write guard.** Below `OPENMETADATA_STORAGE_MIN_FREE`, SPARQL updates and Graph Store uploads and
  deletes get HTTP 507 Insufficient Storage before they reach TDB2. Reads and compaction are not
  refused, so the volume can always be compacted back above the reserve.
- **A storage report.** `GET /$/openmetadata/storage` (admin only) returns the volume's
  `totalBytes`, `usableBytes` and `reserveBytes`, and `lowOnSpace`, whether writes are refused.

OpenMetadata reads the report to compact before the reserve is reached, and retries a 507 without
counting it as a failed delivery.

The image also starts the JVM with `-XX:-CreateCoredumpOnCrash`: a crash core dump is as large as
the heap and lands on the node's disk rather than the data volume.

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
docker run --rm --user 0 --entrypoint chown -v <volume>:/fuseki openmetadata/fuseki:6.3.0 -R 1000:0 /fuseki
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
incompatible Fuseki fails the build. The smoke test needs `docker`, `curl`, `awk` and `jq`.

Fuseki 6.3.0 bundles Apache Shiro 2.2.1 inside `fuseki-server.jar`, which carries a published CVE. The
`server` stage of the Dockerfile replaces it with the release listed in
`server-jar/replacements.sha256`, downloaded from Maven Central and checked by SHA-256; the rest of
Apache's jar is unchanged. Fuseki's own classes call the same Shiro methods when compiled against the
new release, and Fuseki's test suite gives the same results on it. Fuseki's bundled Micrometer 1.17.2
already has the fix for its CVE, so Micrometer is not replaced. Drop the stage when a Fuseki release
ships a fixed Shiro.

`mvn verify` in `extension/` runs the unit tests and the Graph Store integration tests on a real Fuseki
server: upload deadlines and size limits, rollback, and the storage guard. The smoke test starts the
built image through every environment contract above, including the storage guard's `507` and a 150,000 character RDF Thrift row, and checks
that `/reasoning/*` is not served.

## Publishing

Images are published only from `release/<fuseki-version>` branches, never from `main`. A release branch
is cut from the matching upstream tag (`jena-<version>` for `release/<version>`), and its name must match the
`FUSEKI_VERSION` in the Dockerfile.

A publish (a push to the release branch, or a manual run of the workflow with the branch as input)
runs in three jobs of `.github/workflows/openmetadata-fuseki-image.yml`:

1. **Build and push.** After the extension tests and the local smoke test pass, it pushes one
   multi-architecture image (`linux/amd64`, `linux/arm64`) under the immutable tag
   `<version>-<commit>` only, then pulls the pushed amd64 image by its digest and runs
   `test/smoke-test.sh <image>@<digest>` against it.
2. **arm64.** A native arm64 runner pulls the same digest for `linux/arm64` and runs the same smoke
   test. The build ran arm64 only under emulation; this is the first time it starts natively.
3. **Promote.** Once both pass, `docker buildx imagetools create` points `<version>` at that digest
   without rebuilding, and the job fails unless `<version>` then resolves to exactly the tested digest.

So `<version>` moves only to a digest that passed the smoke test on both architectures as pushed.
A `<version>-<commit>` tag whose run failed after the push stays in the registry but is never
promoted; do not use it. Pull requests build and smoke-test the image and publish nothing. Pin
`<version>-<commit>`, or `<version>@<digest>`, in tests and deployments; `<version>` moves with
each release build.
Each publish pushes two tags for `linux/amd64` and `linux/arm64`: `<version>`, which moves to the latest
build of that release, and the immutable `<version>-<commit>`. Pin the immutable tag in deployments
and tests: with `imagePullPolicy: IfNotPresent`, a node that already has `<version>` never pulls a
rebuild of it.

## Porting to a new Fuseki release

A release branch is the upstream tag plus `openmetadata/fuseki/` and the image workflow, nothing
else. `bin/port-to` carries that to the next release without touching the upstream tree:

```bash
git switch <branch that carries the packaging>
openmetadata/fuseki/bin/port-to <new-version>       # --no-checks skips the build and tests
```

It fetches `jena-<new-version>` if needed, creates the local branch `openmetadata/port-<new-version>`
at that tag, merges the branch it was run from, and fails if the result differs from the tag
anywhere outside `openmetadata/` and the workflow. One commit then bumps `FUSEKI_VERSION`, its
SHA-512 (from Apache's checksum file), the URL comment and the extension's `<version>` and
`<jena.version>`. It lists every other mention of the old version; prose such as the README's version
statements is yours to review. Then it runs `mvn verify`, builds `om-fuseki:<new-version>-port` and
smoke-tests it. Rerunning it converges to the same branch, so run it again after fixing what it found.
It never pushes.

What fails after a port, and what it means:

- **Compile errors in the extension.** Fuseki changed an API the extension uses. Fix the extension.
- **`ReplacementsTripwireTest`.** A library listed in `server-jar/replacements.sha256` is no longer
  newer than the one this Fuseki bundles, or is no longer bundled. The new Fuseki has fixed that
  library, so delete its lines from the list; `replace-libraries.sh` then stops deleting it from the
  jar. Correct the README and Dockerfile wording about it too. The test also fails when a bundled
  artifact of a replaced library is missing from the list.
- **`StockFusekiLockIT`.** Stock Fuseki no longer holds the writer lock while an upload transfers, the
  reason for the extension. Repeat the measurement in "What the extension adds" before keeping or
  removing it.
- **`StockThriftRowCapIT`.** Jena's own RDF Thrift reader accepts a row above 100,000 bytes, so the
  cap that `ThriftRows` lifts is gone or configurable. Read `TRDF` in the new release. If the cap is
  gone, delete `ThriftRows`, the Thrift branch of `BoundedGraphStore.parse`, `ThriftRowCapIT` and the
  smoke-test check, and correct "What the extension adds". If it is configurable, set it from
  `OPENMETADATA_MAX_UPLOAD_BYTES` instead of overriding the reader. Also compile errors in
  `ThriftRows` mean `TRDF`, `ThriftRDF` or Thrift's `TIOStreamTransport` changed.
- **A smoke-test failure.** An environment contract in this README no longer holds on the new Fuseki.

Publishing is by pull request. The pull request's CI builds and smoke-tests the image and publishes
nothing; the merge is what publishes:

```bash
git push origin jena-<new-version>^{commit}:refs/heads/release/<new-version>   # exactly the upstream tag
# open a pull request from openmetadata/port-<new-version> into release/<new-version>
```
