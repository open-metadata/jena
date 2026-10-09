# Apache Jena Fuseki packaged by OpenMetadata

A Docker image of the released Apache Jena Fuseki distribution with an optional OpenMetadata Graph
Store extension and an optional OWL reasoner worker. **This is not an Apache Software Foundation
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
| `OPENMETADATA_REASONING_ENABLED` | `false` | `true` lets the extension run reasoner workers once the memory budget fits. Needs `OPENMETADATA_EXTENSION_ENABLED=true`. See [Reasoner](#reasoner). |
| `OPENMETADATA_REASONER_HEAP` | `2g` | Worker heap `H_w`, its `-Xms` and `-Xmx`. At least `16m`. |
| `OPENMETADATA_REASONER_OFF_HEAP` | `512m` | Worker off-heap budget `O_w`. At least `512m`, what the worker's flags cap. |
| `OPENMETADATA_REASONER_PAGE_CACHE_FLOOR` | `50%` | Page cache kept for serving, `C_min`: a size, or a percentage of the serving datasets' size on disk. |
| `OPENMETADATA_REASONER_CPUS` | half the container's CPUs, at least 1 | Worker `-XX:ActiveProcessorCount`. |

Invalid values stop the container at start with an `ERROR` line naming the variable. Numeric values
must be positive integers; sizes use the JVM's notation (`512m`, `2g`); booleans must be `true` or
`false`. The reasoner variables are validated whenever they are set, even with reasoning off.

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
| `/fuseki` | Volume (`FUSEKI_BASE`): `configuration/`, `databases/`, `shiro.ini`, `extra/`, and `reasoning/` when reasoning is enabled |
| `/jena-fuseki/reasoner` | The reasoner worker: its jar and dependencies, `bin/reasoner`, `NOTICE`, `THIRD-PARTY.md`, `licenses/` and the HermiT source in `source/` |

The extension is enabled by a symlink `/fuseki/extra/openmetadata-fuseki-extensions.jar` pointing at the
jar in the image, so it always matches the running image. Other jars in `extra/` are left alone.

## Reasoner

The image can classify and explain OWL ontologies beside the data. All of it runs in short-lived
**worker** JVMs that the extension starts inside the container, one step at a time, never in the
Fuseki JVM: a worker that runs out of heap or time ends with a Java error in its own process, and
Fuseki, its heap and its serving datasets are untouched. The worker opens only its job's private
TDB2 dataset under `/fuseki/reasoning`, has no network listener and never fetches anything.

This release adds the worker, its launcher and the memory-budget check. The extension's HTTP job
operations that OpenMetadata will call come in a later release; until then reasoning runs only
through `bin/reasoner` (below).

### Engines

| Engine | Used for | Licence |
|---|---|---|
| [HermiT](https://github.com/open-metadata/hermit-reasoner), OpenMetadata's build of upstream `master` | Every closure outside the OWL 2 EL profile, and every one that needs property-value or equality entailments | LGPL-3.0-or-later |
| [ELK](https://github.com/liveontologies/elk-reasoner) 0.6.0 | Closures in OWL 2 EL that use nothing ELK reports as possibly incomplete | Apache-2.0 |

Both are used only through the OWL API 5.5.1 interfaces. A closure goes to ELK only when it is in the
OWL 2 EL profile and uses none of the constructs ELK 0.6.0 answers incompletely (data properties,
nominals, keys, anonymous individuals, property ranges together with assertions); if ELK still
reports possible incompleteness, the closure is classified again with HermiT. Nothing is ever
dropped to make a closure fit an engine.

HermiT runs with an explicit configuration: unsupported datatypes are errors, never ignored;
queries about unknown entities are refused; deadlines interrupt it. **Disjunction learning is off.**
It is a search heuristic and does not change answers. Measured on this build with OWL API 5.5.1 and
Java 21: with it on, two W3C OWL 2 conformance cases (WebOnt-description-logic-208 and -209) did not
finish within 20 minutes, and 2.3% of 300 runs of case 661 took over 20 s. With it off, all 350
applicable W3C cases pass in about 5 s, 208 and 209 in under 0.7 s, and 661's slowest run takes about
1 s. The cost is a 1.65x slower classification of the wine ontology (4.2 s against 2.5 s) and a
slower case 201 (317 ms median and 6 s at most, against under 1 ms). Every answer was identical
either way. Hard inputs remain: the step's deadline bounds them, and they end `INCOMPLETE`.

### Steps

The extension writes a JSON step file into a job directory under `/fuseki/reasoning` and starts a
worker on it. Every path a step names must stay inside its job directory.

| Step | Does |
|---|---|
| `LOAD` | Bulk-loads exported N-Quads or TriG (optionally gzip-compressed) into a fresh private TDB2 dataset |
| `CLASSIFY` | Classifies one import closure: a set of named graphs whose `owl:imports` must all resolve inside it. Writes consistency, unsatisfiable classes, the direct class hierarchy, equivalences and direct types (and, with HermiT, property values and equalities between named individuals) into an output graph, and answers entailment checks with `ENTAILED`, `NOT_ENTAILED`, `INCONSISTENT` or `INVALID`. `NOT_ENTAILED` never means the negation was proven. An inconsistent closure writes nothing. |
| `EXPLAIN` | Computes one justification for an entailment or an inconsistency |
| `COMPACT` | Compacts the dataset before it is published |

A closure outside OWL 2 DL, with a missing import, with triples the OWL mapping cannot consume, or
over an admission limit is rejected with its problems, never classified with something dropped.

Each step writes `<step>.result.json`, and the launcher writes `<step>.status.json` and
`<step>.log`. The status is one of:

| Status | Meaning |
|---|---|
| `SUCCEEDED` | The step completed; `reason` is its outcome, which may be `INCONSISTENT` |
| `FAILED` | The step or its input was rejected (`INVALID_STEP`, `INVALID_INPUT`, `NOT_OWL2_DL`, `LIMIT_EXCEEDED`), or the worker failed |
| `INCOMPLETE` | A budget ran out: `MEMORY_BUDGET` (the worker exhausted its heap, exit status 3) or `TIME_BUDGET` (it passed its deadline); nothing it produced is complete |
| `INTERRUPTED` | The worker was killed by something other than its own budgets, or cancelled |
| `NOT_READY` | The memory budget does not fit; no worker was started |
| `BUSY` | Another worker is running; at most one runs per container |

A worker enforces its own deadline by interrupting the engine. If it has not exited 15 seconds
after its deadline it gets `SIGTERM`, and `SIGKILL` 10 seconds later.

### Memory budget

A worker shares the container's memory limit with Fuseki. To keep a worker's out-of-memory a Java
error inside the worker, never a kernel OOM kill of the whole container, every JVM region is capped
and reasoning is admitted only when:

```text
container memory limit >= (H_f + O_f + H_w + O_w + C_min) / 0.9
```

| Term | Meaning | Value |
|---|---|---|
| `H_f` | Fuseki heap | `FUSEKI_HEAP`, or the last `-Xmx` in `JVM_ARGS` |
| `O_f` | Fuseki off-heap | 1 GiB, 1.5 GiB from an 8 GiB heap |
| `H_w` | Worker heap | `OPENMETADATA_REASONER_HEAP` |
| `O_w` | Worker off-heap: metaspace, code cache and direct memory are capped at 256, 128 and 64 MiB | `OPENMETADATA_REASONER_OFF_HEAP` |
| `C_min` | Page cache kept for serving | `OPENMETADATA_REASONER_PAGE_CACHE_FLOOR`; 50% of `/fuseki/databases` by default |

The limit is read from cgroup v2 `memory.max`, else cgroup v1, else physical memory when the
container has no limit. The values of `O_f` and `O_w` are starting values until they are measured.

For example, `FUSEKI_HEAP=512m`, `OPENMETADATA_REASONER_HEAP=1g` and
`OPENMETADATA_REASONER_PAGE_CACHE_FLOOR=64m` need `(512 + 1024 + 1024 + 512 + 64) MiB / 0.9`, so
`--memory=3485m` or more; the smoke test runs exactly there. The defaults, a 4 GiB Fuseki heap and
a 2 GiB worker heap, need about 8.3 GiB plus the page-cache floor.

The check runs when the server starts, which logs a line such as:

```text
INFO  Reasoning :: Reasoning READY: requires 3.4 GiB (3653704818), available 3.4 GiB (3654287360) (cgroup v2 memory.max)
WARN  Reasoning :: Reasoning NOT_READY: requires 3.4 GiB (3653704818), available 1.0 GiB (1073741824) (cgroup v2 memory.max); The container memory limit ...
```

It runs again before every step, which also refuses to start a worker while Fuseki's own memory
exceeds `H_f + O_f`. When the budget does not fit, steps end `NOT_READY` and no worker starts;
serving is never affected. Raise the container limit with `OPENMETADATA_REASONER_HEAP`, never only
the heap.

Keep `/fuseki` on a disk-backed volume: a memory-backed tmpfs, such as a Kubernetes `emptyDir` with
`medium: Memory`, counts against the same limit. On Kubernetes set `resources.requests.memory`
equal to `limits.memory`.

### Running it by hand

`bin/reasoner` runs the same settings, budget check, lock and launch command as the extension:

```bash
docker exec <container> /jena-fuseki/reasoner/bin/reasoner readiness
docker exec <container> /jena-fuseki/reasoner/bin/reasoner run /fuseki/reasoning/jobs/<job>/<step>.json
docker exec <container> /jena-fuseki/reasoner/bin/reasoner command /fuseki/reasoning/jobs/<job>/<step>.json
```

`readiness` prints the budget as JSON, with `status` (`READY`, `NOT_READY` or `DISABLED`),
`requiredBytes`, `availableBytes`, `limitSource`, each term, Fuseki's current anonymous memory and
the reasons. `command` prints the worker's launch command:

```bash
choom -n 1000 -- nice -n 10 ionice -c2 -n7 java -Xms<H_w> -Xmx<H_w> -Xss1m \
  -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=64m \
  -XX:ActiveProcessorCount=<cpus> -XX:+UseParallelGC -XX:+ExitOnOutOfMemoryError \
  -Djava.io.tmpdir=<job directory> -cp '/jena-fuseki/fuseki-server.jar:/jena-fuseki/reasoner/*' \
  org.openmetadata.reasoner.Main <step file>
```

### Licences

The worker's jars are listed with their licences in `/jena-fuseki/reasoner/THIRD-PARTY.md`, with the
licence texts in `licenses/`. HermiT is LGPL-3.0-or-later and ships as its own jar; its complete
source is in `/jena-fuseki/reasoner/source/` and at the commit `HERMIT_COMMIT` of
[open-metadata/hermit-reasoner](https://github.com/open-metadata/hermit-reasoner). The image build
fails if any other jar differs from `reasoner/dependencies.sha256`.

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
(cd reasoner && mvn -B verify)
docker build -t openmetadata-fuseki:local .
./test/smoke-test.sh openmetadata-fuseki:local
```

The image build downloads the release from Apache's CDN, falling back to the archive, and checks its
published SHA-512. The extension is compiled against the exact `fuseki-server.jar` it runs with, so an
incompatible Fuseki fails the build. The smoke test needs `docker`, `curl`, `awk` and `jq`.

The reasoner depends on OpenMetadata's HermiT build, which has no release yet. Install it once from
the commit the Dockerfile pins, as the image build and the workflow do:

```bash
commit=$(sed -n 's/^ARG HERMIT_COMMIT=//p' Dockerfile)
git clone https://github.com/open-metadata/hermit-reasoner.git /tmp/hermit-reasoner
git -C /tmp/hermit-reasoner checkout "$commit"
(cd /tmp/hermit-reasoner && mvn -B -Dmaven.test.skip=true -Dmaven.javadoc.skip=true install)
```

`mvn verify` in `reasoner/` runs the conformance suite, which has fifty fixtures covering every
authorable OWL construct, imports, inconsistency and open-world cases, each with a hand-derived
expected report. It runs every fixture that routes to ELK through HermiT as well and requires
identical results. Its integration tests run the packaged worker on the image's classpath. The smoke
test runs the same suite through the built image.

## Publishing

Images are published only from `release/<fuseki-version>` branches, never from `main`. A release branch
is cut from the matching upstream tag (`jena-6.2.0` for `release/6.2.0`), and its name must match the
`FUSEKI_VERSION` in the Dockerfile.

Each publish pushes two tags for `linux/amd64` and `linux/arm64`: `<version>`, which moves to the latest
build of that release, and the immutable `<version>-<commit>`. Pin the immutable tag in tests.
