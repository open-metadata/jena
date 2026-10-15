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
| `OPENMETADATA_REASONING_MAX_QUEUED_JOBS` | `16` | Reasoning jobs that may wait behind the running one; more get HTTP 429. At most 4 digits. |
| `OPENMETADATA_REASONING_JOB_HISTORY` | `1000` | Finished jobs kept, with their request IDs and directories. At most 4 digits. |
| `OPENMETADATA_REASONING_JOB_RETENTION_HOURS` | `168` | How long a finished job is kept. |
| `OPENMETADATA_REASONING_DISK_QUOTA` | `25%` | Disk all of `/fuseki/reasoning` may use before jobs are refused: a size, or a percentage of the volume's capacity. |
| `OPENMETADATA_REASONING_MAX_JOB_SECONDS` | `3600` | How long one job may run, capture included; each worker step gets what is left as its deadline. At most 6 digits. |

Invalid values stop the container at start with an `ERROR` line naming the variable. Numeric values
must be positive integers; sizes use the JVM's notation (`512m`, `2g`) with at most 13 digits;
booleans must be `true` or `false`. An empty value counts as unset. The reasoner variables are
validated whenever they are set, even with reasoning off, and the extension and `bin/reasoner`
read them with the same rules; `test/reasoning-settings.cases` lists the values both are tested
against.

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
| `/fuseki` | Volume (`FUSEKI_BASE`): `configuration/`, `databases/`, `shiro.ini`, `extra/`, and `reasoning/` when reasoning is enabled, with a directory per job in `reasoning/jobs/` |
| `/jena-fuseki/reasoner` | The reasoner worker: its jar and dependencies, `bin/reasoner`, `NOTICE`, `THIRD-PARTY.md`, `licenses/` and the HermiT source in `source/` |

The extension is enabled by a symlink `/fuseki/extra/openmetadata-fuseki-extensions.jar` pointing at the
jar in the image, so it always matches the running image. Other jars in `extra/` are left alone.

## Reasoner

The image can classify and explain OWL ontologies beside the data. All of it runs in short-lived
**worker** JVMs that the extension starts inside the container, one step at a time, never in the
Fuseki JVM: a worker that runs out of heap or time ends with a Java error in its own process, and
Fuseki, its heap and its serving datasets are untouched. The worker opens only its job's private
TDB2 dataset under `/fuseki/reasoning`, has no network listener and never fetches anything.

This release adds the worker, its launcher, the memory-budget check and the HTTP job operations
OpenMetadata calls ([Job operations](#job-operations)). `CHECK` and `EXPLAIN` run end to end: the
extension captures the closure OpenMetadata pinned from the serving dataset
([Capture](#capture)), and a worker loads it and classifies or explains it. `REFRESH`, snapshot
results and snapshot queries arrive with reasoning snapshots in a later release.

### Engines

| Engine | Used for | Licence |
|---|---|---|
| [HermiT](https://github.com/open-metadata/hermit-reasoner), OpenMetadata's build of upstream `master` | Every closure outside the OWL 2 EL profile, and every one that needs equality or property values beyond the asserted ones | LGPL-3.0-or-later |
| [ELK](https://github.com/liveontologies/elk-reasoner) 0.6.0 | Closures in OWL 2 EL that use nothing ELK reports as possibly incomplete | Apache-2.0 |

Both are used through the OWL API 5.5.1 interfaces, plus HermiT's per-property instance query. A
closure goes to ELK only when it is in the OWL 2 EL profile and uses none of the constructs ELK 0.6.0
answers incompletely (data properties, nominals, keys, anonymous individuals, property ranges
together with assertions); if ELK still reports possible incompleteness, the closure is classified
again with HermiT. Nothing is ever dropped to make a closure fit an engine.

ELK computes no property values. Most object properties of a catalog have only asserted values,
which the worker reads from the closure itself; an engine is asked only for properties that can
gain values: those with a sub-property, an equivalent or inverse property, a property chain, or
symmetry or transitivity over a property with values, reflexive properties, and properties in
has-value or has-self restrictions. Nominals, anonymous individuals, the universal or empty
property, and has-value or has-self restrictions together with functional or maximum-cardinality
constraints can connect individuals in ways no per-property rule sees; with any of them, HermiT is
asked about every property. A closure in OWL 2 EL whose values are all asserted stays with ELK.

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
| `CLASSIFY` | Classifies one import closure: a set of named graphs whose `owl:imports` must all resolve inside it. Writes consistency, unsatisfiable classes, the direct class hierarchy, equivalences, direct types and object property values between named individuals, asserted and entailed (and, with HermiT, equalities between them) into an output graph, and answers entailment checks with `ENTAILED`, `NOT_ENTAILED`, `INCONSISTENT` or `INVALID`. `NOT_ENTAILED` never means the negation was proven. An inconsistent closure writes nothing. |
| `EXPLAIN` | Computes justifications for an entailment or an inconsistency: one by default, up to `explain.maxJustifications` (at most 10) from a hitting set tree. An entailment of an inconsistent closure is answered `INCONSISTENT` with the inconsistency's justification. Running out of checks after the first justification keeps those found and says so (`justificationsIncomplete`) |
| `COMPACT` | Compacts the dataset before it is published |

A closure is either the listed `closure.graphs` or, with `"allGraphs": true`, every named graph of
the working dataset, as when it holds exactly one captured closure. An `owl:imports` target must be
the IRI or version IRI of an ontology header in the closure, or the name of one of its graphs.
`CLASSIFY`'s manifest names the first 100 unsatisfiable classes in IRI order (`unsatisfiable`);
the count in `entailments` is always complete. A check that cannot be asked of the closure is
answered `INVALID` with its `reason`.

A closure outside OWL 2 DL, with a missing import, with triples the OWL mapping cannot consume, or
over an admission limit is rejected with its problems, never classified with something dropped.

A step file may set any admission limit in `limits`; each must be a positive integer. The limits
that bound the worker's heap default to a size proportional to it, so raising
`OPENMETADATA_REASONER_HEAP` admits larger closures without changing step files. The manifest
records the limits a step ran with.

| Limit | Default with the 2 GiB default heap | Proportional to the heap |
|---|---|---|
| `maxClosureTriples` | 300,000 | yes |
| `maxLogicalAxioms` | 200,000 | yes |
| `maxIndividuals` | 50,000 | yes |
| `maxOutputTriples` | 1,000,000 | yes |
| `maxInputBytes` | 16 GiB, read by `LOAD` as a stream | no |
| `maxExpressionDepth` | 32 | no |
| `maxChecks` | 1,000 entailment checks per `CLASSIFY` | no |
| `maxExplainChecks` | 1,000 consistency checks per `EXPLAIN`, each on a fresh reasoner | no |

They were measured on a catalog-shaped closure classified with HermiT: 50,000 individuals in a
transitive containment hierarchy with its inverse and three assertions each (297,000 triples,
247,000 logical axioms) wrote 581,000 triples in 44 s and peaked at 0.67 GiB of a 2 GiB heap after
collection. The axiom limit is lower than that, because terminological axioms cost the engines more
than assertions.

Each step writes `<step>.result.json`, and the launcher writes `<step>.status.json` and
`<step>.log`. A step file with `"report": true` also gets `<step>.report.txt`, a sorted listing of
the step's answer for tests and diagnosis; for `CLASSIFY` it repeats the output graph, so it is off
by default and keeps only its first 100,000 lines. `CLASSIFY` writes entailments into the output
graph as it finds them, inside one transaction that commits only when the step completes, so a step
that fails or runs out of budget leaves the previous contents of the graph in place. The status is
one of:

| Status | Meaning |
|---|---|
| `SUCCEEDED` | The step completed; `reason` is its outcome, which may be `INCONSISTENT` |
| `FAILED` | The step or its input was rejected (`INVALID_STEP`, `INVALID_INPUT`, `NOT_OWL2_DL`, `LIMIT_EXCEEDED`), or the worker failed |
| `INCOMPLETE` | A budget ran out: `MEMORY_BUDGET` (the worker exhausted its heap, exit status 3, or was killed by a `SIGKILL` the launcher did not send, exit status 137, almost always the cgroup out-of-memory killer) or `TIME_BUDGET` (it passed its deadline); nothing it produced is complete |
| `INTERRUPTED` | The step was cancelled, or the worker was killed by another signal |
| `NOT_READY` | The memory budget does not fit; no worker was started |
| `BUSY` | Another worker is running; at most one runs per container. No status file is written, since the running step may be the same file |

A worker enforces its own deadline by interrupting the engine, and repeats the interrupt every
50 ms until the step unwinds: HermiT clears its interrupt flag whenever a reasoning task starts, so
an interrupt sent once between two tasks, such as the consistency checks of an explanation, was
lost and the next task ran to its end. A step that has not unwound 5 seconds after its deadline
ends the worker JVM. If the worker has not exited 15 seconds after its deadline it gets `SIGTERM`,
and `SIGKILL` 10 seconds later. Cancelling a step sends
`SIGTERM` at once and `SIGKILL` 10 seconds later; a cancellation applies only to the step running
when it is made. If the thread waiting for a worker is interrupted, the worker is killed and the
launcher waits for it to exit before releasing the worker lock.

The launcher takes the container's worker lock before it checks the memory budget, so a step
requested while a worker runs ends `BUSY`, never `NOT_READY` because of the running worker's memory.
After a step that did not succeed, it removes the step's exported closure and any half-written
`*.tmp` files from the job directory. A step's log keeps the first and the last 512 KiB of the
worker's output. The worker inherits only `PATH`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TZ` and `HOME` from
the server's environment, so `ADMIN_PASSWORD` and JVM options meant for Fuseki never reach it.

### Job operations

The extension serves the reasoning protocol that OpenMetadata defines in its JSON Schemas
(`openmetadata-spec/.../api/rdf/rdfReasoningJobRequest.json`, `rdfReasoningJob.json` and
`rdfReasoningCapabilities.json`); `extension/src/test/resources/openmetadata-contract/` holds the copy
the tests check against. The operations are served whenever the extension is enabled; with
`OPENMETADATA_REASONING_ENABLED` not `true` they report `DISABLED` and refuse jobs.

| Request | Answer |
|---|---|
| `GET /reasoning/capabilities` | `200`: protocol version `1`, readiness (`READY`, `NOT_READY` or `DISABLED`) with its reasons, the operations (`REFRESH`, `CHECK`, `EXPLAIN`, `RESULTS`, `QUERY`, each `AVAILABLE` or `NOT_IMPLEMENTED` with a reason), the memory budget (container limit, required bytes and each term), and the limits the store enforces (`maxQueuedJobs`, `maxDiskBytes`) |
| `POST /reasoning/jobs?dataset=<name>` | `202` with the new job and a `Location`; `200` with the existing job when its `requestId` is known; `400` for a request the schema refuses; `404` for a dataset that is not served; `409` for a known `requestId` sent with a different request or dataset; `413` over 4 MiB; `415` unless `application/json`; `429` when the queue is full; `503` when reasoning is disabled or not ready, or the disk quota is reached |
| `GET /reasoning/jobs/<id>` | `200` with the job: state, `cancelRequested`, input, progress, outcome and problem, times; `404` when unknown |
| `DELETE /reasoning/jobs/<id>` | Requests cancellation: `202` while the job stops, `200` once it has, or for a job that had already finished, which is unchanged |
| `GET /reasoning/results/<id>`, `POST /reasoning/query` | `501` with the problem `NOT_IMPLEMENTED` until snapshots arrive; a finished check's detail is in its job directory's `result.json` meanwhile |

Every refusal answers with the protocol's problem object, `{"code": ..., "message": ...}`, with
`subject` naming the limit for `LIMIT_EXCEEDED`. A request is validated against the schema and the
schemas it references, including the per-operation requirements (a `REFRESH` needs
`ruleBundleDigest` and only accepts `APPROVED` ontologies; `CHECK` and `EXPLAIN` need `ontologyIri`
and their question), before anything is stored. It may nest at most 64 levels deep.

**Routing.** Fuseki dispatches a dataset's operations by endpoint name, so `/reasoning/jobs/<id>`
cannot be one of them. The module registers one servlet at `/reasoning/*`, which routes the path
itself; the serving dataset a job reads is the `dataset` parameter, checked against the datasets the
server serves. A dataset named `reasoning` would hide the operations, and the server logs a warning.

**Execution.** One job runs at a time, in submission order, on a thread of its own, never on a
request thread. Its worker steps go through the launcher above, so they also take the container's
worker lock: a step run by hand with `bin/reasoner` makes the job's step wait, for up to 10 minutes.
Each job has a directory `/fuseki/reasoning/jobs/<id>/` with the request as received
(`request.json`), its manifest (`job.json`, replaced atomically on every change) and its steps'
files. A job's unpublished work, its working datasets and captured input, is removed when it
ends; the request, manifest, step files and logs stay until the job leaves the history.

**Cancellation.** A queued job is `CANCELLED` at once. A running job stays `RUNNING` with
`cancelRequested` until its current step has stopped: the worker gets `SIGTERM`, and `SIGKILL` 10
seconds later. A step that starts after the request cancels itself, and no later step starts.

**Restart.** When the server starts, every job that had not finished becomes `INTERRUPTED`, its
unpublished work is removed, and any worker its directory records is killed, but only while that
process is still the worker of a step in that directory. Stopping the server kills the running
worker and interrupts its job. A request ID keeps returning its job as long as the job is kept.

**Disk.** Before admitting a job the extension measures all of `/fuseki/reasoning`, including steps
run by hand, and refuses work at or over `OPENMETADATA_REASONING_DISK_QUOTA`. Finished jobs beyond
`OPENMETADATA_REASONING_JOB_HISTORY` or older than `OPENMETADATA_REASONING_JOB_RETENTION_HOURS` are
removed, oldest first. Directories without a `job.json` are never removed.

**What a job does in this release.** A `CHECK` runs `capture`, `load` and `classify`; an
`EXPLAIN` runs `capture`, `load` and `explain`. The job's `outcome` is the protocol's answer:

| Result | Job |
|---|---|
| Consistent closure, `CONSISTENCY` | `SUCCEEDED`, `NOT_ENTAILED` |
| `SATISFIABILITY`, `SUBSUMPTION`, `ENTAILMENT`, or `EXPLAIN` | `SUCCEEDED`, `ENTAILED` or `NOT_ENTAILED` (`SATISFIABILITY` asks whether the class is a subclass of `owl:Nothing`) |
| Inconsistent closure | `SUCCEEDED`, `INCONSISTENT` |
| A limit, the job's time, or the worker's heap ran out | `SUCCEEDED`, `INCOMPLETE`, with the problem `LIMIT_EXCEEDED` naming the limit (`maxImports`, `maxClosureAxioms`, `maxIndividuals`, `maxExpressionDepth`, `maxDiskBytes`, `maxJobSeconds`) or `MEMORY_BUDGET_EXHAUSTED` |
| Closure outside OWL 2 DL | `FAILED`, `PROFILE_VIOLATION` |
| No manifest for the request, or a manifest or import that is not one | `FAILED`, `MISSING_IMPORT`, `subject` the ontology or manifest |
| A pinned graph or manifest whose content changed | `FAILED`, `GENERATION_MISMATCH`, `subject` the graph or version IRI |
| A question naming an entity outside the closure | `FAILED`, `INVALID_REQUEST` |
| Cancelled | `CANCELLED` |

The job's `input.engines` names the engine build that answered. `result.json` in the job directory
holds the detail: the unsatisfiable classes (at most 100, with the full count) and the check's
axiom for a `CHECK`, the justifications for an `EXPLAIN`, and the closure's admission counts.
`capture.json` lists the captured graphs. A question the schema admits but OWL cannot state, such as
an intersection without operands, is refused with `400` when the job is submitted.

Capabilities report `CHECK` and `EXPLAIN` `AVAILABLE` with the limits the protocol requires of them
(`maxQueuedJobs`, `maxJobSeconds`, `maxDiskBytes`, `maxClosureAxioms`, `maxIndividuals`,
`maxExpressionDepth`, `maxImports`), the engine builds read from the worker jar's manifest, and the
OWL 2 DL constructs as coverage; `REFRESH`, `RESULTS` and `QUERY` stay `NOT_IMPLEMENTED`, so a
`REFRESH` still fails at `capture` with `NOT_IMPLEMENTED`, which is permanent for this build.
`NOT_READY` stays the transient answer (reasoning disabled, memory budget, disk unmeasurable, server
stopping, another worker running). An operation is `AVAILABLE` only while the store enforces every
limit it needs (OpenMetadata's
`ADR:2026-10-14-rdf-reasoning-capabilities-list-each-operation-and-the-limits-it-enforces`); when
the disk quota cannot be resolved, `CHECK` and `EXPLAIN` are reported `NOT_IMPLEMENTED` and
readiness `NOT_READY`. The admission limits are the worker's for `OPENMETADATA_REASONER_HEAP` and
are written into every step file, so the worker enforces exactly what capabilities report.

### Capture

OpenMetadata keeps each ontology axiom, term and relationship type in a named graph of its own,
and pins a closure in a content-addressed version manifest, the graph
`{base}graph/manifest/<hex>` (OpenMetadata's
`ADR:2026-10-15-reasoning-closures-are-approved-graphs-pinned-by-a-content-manifest` and
`docs/rdf-ontology-contract.md`). A request names its closure with `ontologyIri`,
`ontologySelection` and `ontologyDigest`, the closure digest. The capture finds the manifest by the
version IRI those give (`<ontologyIri>/version/<hex>`, or `/candidate/<hex>` for a candidate) and
checks the graph's name; an `APPROVED` request without a digest uses the manifest
`{base}graph/ontology/activations` names for the ontology. The store never needs OpenMetadata's
base IRI.

Inside one read transaction of the serving dataset, the capture then, for the manifest and each
manifest it imports (`omr:importsManifest`):

1. recomputes the closure digest (`om-closure-v1`) from the manifest's header and its
   `omr:pinnedDigest` members and compares it with the manifest's `omr:closureDigest`, and the
   root's with the request's `ontologyDigest`;
2. copies the manifest's `owl:Ontology` header (type, version IRI, imports), never its `omr:`
   control triples;
3. reads each pinned graph not copied yet, recomputes its digest and compares it with the pinned
   one, then copies it.

It writes N-Quads, gzip level 1, to `capture.nq.gz` in the job directory, renamed into place only
when complete. Graphs that are not pinned, such as Draft graphs outside a candidate closure, are
never read. A read transaction never blocks Fuseki's writer: a write commits during a capture, which
keeps seeing the version it started with (`CaptureTest`). The server holds one pinned graph at a
time, one manifest's member list and the set of captured graph names, never the closure. Every
4,096 quads the capture checks the triple bound (the worker's `maxClosureTriples`, which protects
`maxClosureAxioms` before parsing), the compressed size against what the disk quota leaves, the
job's deadline and cancellation; at most `maxImports` (10,000) graphs. The working dataset the load
builds is checked against the quota again before anything reasons over it.

**Graph digest.** `sha256:` and the SHA-256 of the graph's N-Triples, one triple per line, lines
sorted in Java string order, each ending in `\n`, with every blank node replaced by
`urn:openmetadata:annex:<hex>`: a blank node's hash starts from the sorted signatures of the triples
it occurs in (neighbouring blank nodes written `_:blank`, itself `_:self`, other terms in
N-Triples), is refined from its neighbours' hashes until it stops changing, at most 16 rounds, and
the nodes, ordered by hash, get `sha256(hash + ":" + index)`. It is OpenMetadata's
`OntologyGraphDigest` over `RdfBlankNodeCanonicalizer`; the store indexes each blank node's triples
once, so a graph costs linear time per round rather than quadratic.
`extension/src/test/resources/digest-vectors/` holds files with the digests OpenMetadata's classes
compute for them; the store must give the same, also after a round trip through TDB2. Blank nodes
whose hashes never separate are ordered by label, which differs between OpenMetadata and the store;
in the trees the OWL 2 RDF mapping writes such nodes are interchangeable, and otherwise the capture
reports the graph as changed, never the reverse. Literals are compared by their lexical form as
stored.

**Measured** on an Apple M-series laptop, Java 21, worker heap 2 GiB (`CapturePerformanceIT`, run
with `-Dreasoning.performance=true`): a catalog closure of 1,801 table graphs and a schema graph,
225,116 triples, 37,900 individuals and 187,304 logical axioms, captured with every digest checked
in 2.2 s to a 1.7 MB spool; loaded in 3.4 s into a 254 MB working dataset (TDB2 allocates its files
in segments); classified with HermiT in 20.3 s, peaking at 0.27 GiB after collection; 27.8 s for the
whole job. A closure five times larger, 1,125,116 triples in 9,002 graphs, captured in 11.3 s in a
JVM of its own with a 256 MiB heap, TDB2's default caches included.

**Access.** The image's `shiro.ini` requires the admin account for every path except `/$/ping`, so
the operations are admin-only. To give a service account reasoning and nothing else, run with
`FUSEKI_MANAGE_SHIRO=false` and your own `shiro.ini`; Shiro's `roles` filter needs every listed
role, so the admin account carries the reasoning role too:

```ini
[main]
plainMatcher = org.apache.shiro.authc.credential.SimpleCredentialsMatcher
iniRealm.credentialsMatcher = $plainMatcher

[users]
admin = admin-pw, admin, reasoning
openmetadata = service-pw, reasoning
reader = reader-pw, reader

[roles]
admin = *

[urls]
/$/ping = anon
/reasoning/** = authcBasic, roles[reasoning]
/** = authcBasic, roles[admin]
```

The extension's integration tests run against this file (`extension/src/test/resources/shiro/`)
and against the image's own `shiro.ini`.

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

`mvn verify` in `reasoner/` runs the conformance suite, which has fifty-three fixtures covering every
authorable OWL construct, imports, inconsistency and open-world cases, each with a hand-derived
expected report. It runs every fixture that routes to ELK through HermiT as well and requires
identical results, and compares the property values it reads from closures with HermiT's for every
property. The reasoner's tests compile the extension's launcher too: its integration tests start the
packaged worker with the launcher's real command, on the image's classpath, and include a
catalog-shaped closure of 20,000 individuals classified in the default 2 GiB heap; unit tests check
that the exit statuses and step file rules the launcher repeats match the worker's. The smoke test
runs the same suite through the built image.

`mvn verify` in `extension/` runs the job operations against stand-in worker processes: queueing,
cancellation, restart recovery and retention on real job directories, and the HTTP operations on a
real Fuseki server with Shiro. It checks every request example, job and capabilities document
against OpenMetadata's schemas, and captures closures from real TDB2 datasets laid out as
OpenMetadata writes them. In `reasoner/`, `OntologyJobsIT` runs `CHECK` and `EXPLAIN` jobs end to
end with the packaged worker: consistent, unsatisfiable, a candidate closure made inconsistent by a
Draft graph, and a changed graph refused. The smoke test loads `test/check-closure.trig` and runs a
`CHECK` and an `EXPLAIN` over it in the image, submits, reads and cancels a job, and
restarts the container to check that the job and its request ID survive.

## Publishing

Images are published only from `release/<fuseki-version>` branches, never from `main`. A release branch
is cut from the matching upstream tag (`jena-6.2.0` for `release/6.2.0`), and its name must match the
`FUSEKI_VERSION` in the Dockerfile.

Each publish pushes two tags for `linux/amd64` and `linux/arm64`: `<version>`, which moves to the latest
build of that release, and the immutable `<version>-<commit>`. Pin the immutable tag in tests.
