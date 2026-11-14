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
| `JVM_ARGS` | unset | Replaces the heap flags entirely (as in the DaSCH image). The settings below are still appended, and so is `-XX:MaxDirectMemorySize=256m` unless `JVM_ARGS` sets that flag. |
| `FUSEKI_QUERY_TIMEOUT_MS` | unset | Server-wide `arq:queryTimeout`, applied to every dataset including existing ones. |
| `FUSEKI_UPDATE_TIMEOUT_MS` | unset | Server-wide `arq:updateTimeout`, applied to every dataset. |
| `FUSEKI_UNION_DEFAULT_GRAPH` | `false` | `true` makes the default graph the union of named graphs for every TDB2 dataset. |
| `OPENMETADATA_EXTENSION_ENABLED` | `false` | `true` loads the OpenMetadata Graph Store extension. |
| `OPENMETADATA_WRITE_TIMEOUT_MS` | `50000` | Extension only: deadline for a Graph Store upload, after which it is rolled back. |
| `OPENMETADATA_MAX_UPLOAD_BYTES` | `67108864` | Extension only: largest accepted upload; larger ones get HTTP 413. |
| `OPENMETADATA_STORAGE_MIN_FREE` | `10%` | Extension only: free space the data volume keeps. Below it every write gets HTTP 507 Insufficient Storage, nothing is applied, and reads and compaction keep working. A size such as `20g`, or a percentage of the volume. See [Disk space](#disk-space). |
| `OPENMETADATA_STORAGE_ROOT` | `$FUSEKI_BASE/databases` | Extension only: the directory holding the TDB2 datasets, which the write guard and the storage report measure. |
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
| `OPENMETADATA_REASONING_MAX_QUERY_SECONDS` | `30` | Deadline of one snapshot query (`maxQuerySeconds`), and so how long a reader can keep a replaced snapshot on disk. At most 4 digits. |
| `OPENMETADATA_REASONING_MAX_RESULT_ROWS` | `10000` | Rows one snapshot query or one page of a result returns (`maxResultRows`). At most 6 digits. |
| `OPENMETADATA_REASONING_MAX_QUERY_SCANNED_QUADS` | `1000000` | Quads one snapshot query may read from the store; a query that reads more stops with `LIMIT_EXCEEDED` (subject `maxQueryScannedQuads`, not a capabilities limit). At most 9 digits. |
| `OPENMETADATA_REASONING_MAX_CONCURRENT_QUERIES` | `4` | Snapshot queries that may run at once; more get HTTP 429. At most 3 digits. |
| `OPENMETADATA_REASONING_MAX_UPLOAD_BYTES` | `64m` | The largest job input one `PUT /reasoning/inputs/...` may carry, the same as a Graph Store upload's limit. At least `1m`. Reported as the capability limit `maxUploadBytes`. |

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
| `/fuseki` | Volume (`FUSEKI_BASE`): `configuration/`, `databases/`, `shiro.ini`, `extra/`, and `reasoning/` when reasoning is enabled, with a directory per job in `reasoning/jobs/` and the published snapshots in `reasoning/snapshots/` |
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
extension captures the ontology release OpenMetadata uploaded
([Capture](#capture)), and a worker loads it and classifies or explains it. A `REFRESH` captures
every active release from the serving dataset and the catalog triples the rules read, classifies
each release, runs the rules the request carries to a fixed point and publishes the result as an
immutable snapshot ([Refresh](#refresh), [Snapshots](#snapshots)), which `QUERY` reads and `EXPLAIN`
explains governance facts from.

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
| `RULES` | Runs a bundle of governance CONSTRUCT rules to a fixed point over selected graphs, writing each rule's conclusions into its output graph and one witness per derived fact into a provenance file beside the dataset ([Governance rules](#governance-rules)) |

A closure is the listed `closure.graphs`; or, with `"graphsFile"`, the graphs a file in the job
directory lists one per line, as a refresh lists each of the closures its working dataset holds;
or, with `"allGraphs": true`, every named graph of the working dataset, as when it holds exactly
one captured closure. A closure lists at most 10,000 graphs. An `owl:imports` target must be
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
| `maxRules` | 256 rules in one `RULES` bundle, enabled or not | no |
| `maxRulePatterns` | 64 triple and path patterns in one rule, EXISTS included | no |
| `maxPasses` | 64 passes of one `RULES` step, the last one that derives nothing included | no |
| `maxNewFacts` | 10,000,000 triples one `RULES` step may add to its output graphs | no |
| `maxSpoolBytes` | 4 GiB, the files one `RULES` step keeps outside the dataset at once: a pass's solutions, the delta it reads and the one it writes, and the witnesses of every pass so far | no |

They were measured on a catalog-shaped closure classified with HermiT: 50,000 individuals in a
transitive containment hierarchy with its inverse and three assertions each (297,000 triples,
247,000 logical axioms) wrote 581,000 triples in 44 s and peaked at 0.67 GiB of a 2 GiB heap after
collection. The axiom limit is lower than that, because terminological axioms cost the engines more
than assertions.

Measured again on the published `6.2.0-600514c` image (Apple M4 Max, `linux/arm64`, two worker
processors), the same shape needs:

| Individuals | Closure triples | Logical axioms | Peak after collection | Classifies in |
|---:|---:|---:|---:|---|
| 10,000 | 59,434 | 49,384 | 0.16–0.23 GiB | 512 MiB, in 9–10 s |
| 20,000 | 118,823 | 98,773 | 0.27–0.36 GiB | 512 MiB, in 16–26 s |
| 50,000 | 296,997 | 246,947 | 0.57–0.65 GiB | 1 GiB, in 46–88 s; a 512 MiB heap runs out |

The 200,000-axiom limit of the default heap therefore admits about 40,000 individuals of this shape;
the 50,000-individual closure needs `OPENMETADATA_REASONER_HEAP=2560m` or a `maxLogicalAxioms` in
its step file.

The `RULES` limits do not grow with the heap: the step keeps its solutions, facts and witnesses on
disk, and its heap does not grow with them. The starter rules over a 551,735-triple lineage catalog
derived 317,365 facts in 45 s and peaked at 43 MiB after collection in a 256 MiB heap; half that
catalog peaked at 27 MiB. `maxNewFacts` and `maxSpoolBytes` bound the disk and time one refresh
may take; `maxRules` and `maxPasses` match OpenMetadata's bundle size and its materializer's pass
budget.

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
| `INCOMPLETE` | A budget ran out: `MEMORY_BUDGET` (the worker exhausted its heap, exit status 3, or was killed by a `SIGKILL` the launcher did not send, exit status 137, almost always the cgroup out-of-memory killer), `TIME_BUDGET` (it passed its deadline), or a `RULES` budget named by the manifest's outcome and `limit`, exit status 6: `PASS_BUDGET` (`maxPasses`), `FACT_BUDGET` (`maxNewFacts`) or `SPOOL_BUDGET` (`maxSpoolBytes`); nothing it produced is complete |
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

### Governance rules

A `RULES` step runs OpenMetadata's governance rules, positive SPARQL `CONSTRUCT` rules such as PII
propagation through column lineage, tag inheritance through containment and domain membership,
over a working dataset that `LOAD` (and `CLASSIFY`, for ontology conclusions) filled:

```json
{"protocol": 1, "stepId": "rules", "kind": "RULES", "dataset": "working", "timeoutMillis": 600000,
 "read": ["urn:capture:asserted", "urn:capture:context"],
 "provenance": "provenance",
 "rules": [{"name": "pii-propagation-via-lineage", "ruleBody": "PREFIX om: <...> CONSTRUCT {...} WHERE {...}",
            "enabled": true, "priority": 200, "output": "urn:capture:inferred:pii-propagation-via-lineage"}],
 "limits": {"maxPasses": 64}}
```

- `read` lists the graphs the rules read; they read the union of these and the output graphs, never
  any other graph. `urn:x-arq:DefaultGraph` names the default graph.
  `readFile` instead names a file in the job directory listing them one per line, at most 100,000,
  as a refresh does.
- `rules` is OpenMetadata's rule shape (`name`, `ruleBody`, `ruleType`, `enabled`, `priority`,
  `displayName`, `description`, `tags`) plus `output`, the graph a rule's conclusions go to, which
  every enabled rule needs. Rules may share an output graph. Order and `priority` do not change the
  result.
- `provenance` names the file the step writes its witnesses to, in the job directory and outside
  the dataset. It is not RDF in the dataset, so no rule and no query can read it.
- No output graph may be read, and no graph under `urn:openmetadata:reasoner:` may be named: the
  step names its delta there in the dataset a pass evaluates over.

**Admission.** Every rule, enabled or not, is checked on its SPARQL algebra before anything runs, and
one invalid rule rejects the bundle (`INVALID_RULES`, every problem listed with its rule). The step
accepts what OpenMetadata's own check accepts, a positive, finite `CONSTRUCT` that SPARQL 1.1 parses:
no `OPTIONAL`, `MINUS`, `NOT EXISTS`, `BOUND`, or `EXISTS` other than as a condition the whole
`FILTER` requires; no nondeterministic functions (`RAND`, `NOW`, `UUID`, `STRUUID`, `BNODE`,
`fn:current-*`); no subqueries, aggregates, `LIMIT`, `OFFSET`, `ORDER BY` or `SERVICE`; no blank
nodes in the template, and every template variable bound by a triple pattern, a property path or
`VALUES`, never only by `BIND`. It is stricter in three places: `GRAPH`, `FROM` and `FROM NAMED` are
refused, since rules read the selected union; only SPARQL's own functions, `fn:` functions and XSD
casts may be called, never extension functions, and property functions are off, since both run code
named by an IRI; and a rule has at most 16,384 characters, 32 template triples, `maxRulePatterns`
patterns and 32 levels of nesting.

**Evaluation.** The step first empties its output graphs and removes its provenance file, so it
always recomputes from what it reads: a premise deleted since the last run takes every conclusion
that only it supported with it, including conclusions around a cycle, and a conclusion with another
support stays. It then runs passes, semi-naively. Pass 1 evaluates every rule in full. Each later
pass evaluates, for every triple pattern that can match the previous pass's new facts (the delta),
the rule with that one pattern matching only them and the rest matching everything, and with UNION
branches that do not hold the pattern dropped. A solution that uses a new fact is found that way;
one that does not was found before.

The delta is a flat file of the job directory, written by one pass and read by the next: it has no
index, so each read of it is one scan of the file. A variant therefore puts the delta pattern first
in its block, where it is read once and drives the join. A rule is evaluated in full in a pass
instead when a property path or EXISTS condition of it reads a predicate the delta holds, or when
one of its delta patterns is not on the leftmost path of the rule's algebra (it follows a property
path or `VALUES` in its group, or sits in a nested group that is joined to patterns before it) and
would be looked up once per solution of what precedes it. Full evaluation finds the same facts in
the same passes.

A pass evaluates the rules and spools their solutions to a file in the job directory; it then reads
the file back and applies the conclusions, so nothing is evaluated while the dataset changes. All
passes run in one write transaction, committed when the step converges or runs out of a budget:
nothing else reads the working dataset, and TDB2 copies an index page again in every transaction
that changes it, so a commit per pass grew the files and synced them with every pass. A conclusion
already in a graph the rules read is not stored; one already in its rule's graph is a duplicate; any other goes into its rule's
graph with a witness and, if no output graph had it, into the next delta. The step converges when a
pass adds no new fact, and reports `CONVERGED` with per-pass and per-rule counts. Running out of
passes, new facts or spool ends it `INCOMPLETE`, and running out of time ends it `TIME_BUDGET`; a
step that does not converge leaves its output graphs partial and no provenance file, so its dataset
must not be published.

The starter rules ship with `transitive-lineage-closure` disabled: it stores every
ancestor/descendant pair, which grows quadratically with lineage depth.

**Witnesses and explanations.** For every fact it derives, the step records the solution that first
derived it: the rule, the pass and the values of the rule's variables. Witnesses go to the step's
provenance file, never into the dataset:

| Part | Holds |
|---|---|
| solutions | the rule, the pass and one term per variable of the rule, in the order derived; facts derived from one solution share it |
| index | one 24-byte entry per derived fact, sorted: a 128-bit SHA-256 digest of the output graph and the triple, and where its solution is |
| run record | the passes, the graphs the rules read, and every enabled rule's body, SHA-256 digest, output graph and variables |

A witness is found from its fact alone, by binary search of the index, which is the only way
anything reads one. The file is written under a temporary name while the passes run, its index
sorted in runs of 65,536 entries that are then merged, and it takes its name only when the step
converges, so a step that does not converge leaves none. Its terms are written out in full, so it
counts against `maxSpoolBytes`. As RDF in the dataset a witness was three or more quads in six
indexes, about 2.6 KB per derived fact and 72% of a snapshot's quads, which every query scanned
past; in the file the 327,600 facts of the 20,000-table catalog below take 49 MB.

The worker's `Explanations` API (used for governance `EXPLAIN`) rebuilds a fact's premises from its
witness, explains each premise in turn down to facts the rules read, reconstructs a property-path
premise as one shortest chain of triples and an EXISTS condition as one match, and marks where it
stopped at its depth, size and path bounds. A derived fact's premises were all present before the
pass that derived it, so an explanation always ends at asserted facts.

### Job operations

The extension serves the reasoning protocol that OpenMetadata defines in its JSON Schemas
(`openmetadata-spec/.../api/rdf/rdfReasoningJobRequest.json`, `rdfReasoningJob.json` and
`rdfReasoningCapabilities.json`); `extension/src/test/resources/openmetadata-contract/` holds the copy
the tests check against. The operations are served whenever the extension is enabled; with
`OPENMETADATA_REASONING_ENABLED` not `true` they report `DISABLED` and refuse jobs.

| Request | Answer |
|---|---|
| `GET /reasoning/capabilities` | `200`: protocol version `1`, readiness (`READY`, `NOT_READY` or `DISABLED`) with its reasons, the operations (`REFRESH`, `CHECK`, `EXPLAIN`, `RESULTS`, `QUERY`, each `AVAILABLE` or `NOT_IMPLEMENTED` with a reason), the memory budget (container limit, required bytes and each term), and the limits the store enforces (`maxQueuedJobs`, `maxDiskBytes`, `maxUploadBytes`, and the rest the protocol requires) |
| `PUT /reasoning/inputs/sha256:<64 hex digits>` | Holds the body, `application/rdf+thrift`, as the input a later job names by that digest. `201` with `{"digest", "byteSize"}` when new; `200` when the same bytes were already held; `400` when the body is empty or does not hash to the digest in the path; `404` for a path that is not `sha256:` and 64 lowercase hex digits; `413` over `maxUploadBytes`; `415` for another content type; `429` when 4 uploads are already being received; `503` when reasoning is disabled or holding it would put `/fuseki/reasoning` over the disk quota (`LIMIT_EXCEEDED`, subject `maxDiskBytes`); `405` for any other method |
| `POST /reasoning/jobs?dataset=<name>` | `202` with the new job and a `Location`; `200` with the existing job when its `requestId` is known; `400` for a request the schema refuses; `404` for a dataset that is not served; `409` for a known `requestId` sent with a different request or dataset; `413` over 4 MiB; `415` unless `application/json`; `429` when the queue is full; `503` when reasoning is disabled or not ready, or the disk quota is reached |
| `GET /reasoning/jobs/<id>` | `200` with the job: state, `cancelRequested`, input, progress, outcome and problem, times; `404` when unknown |
| `DELETE /reasoning/jobs/<id>` | Requests cancellation: `202` while the job stops, `200` once it has, or for a job that had already finished, which is unchanged |
| `GET /reasoning/results/<id>?offset=&limit=` | `200` with a page of a finished job's result ([Results](#results)); `404` for an unknown job or one that has no result, with the job's problem; `409` while it runs; `400` for a limit outside 1 to `maxResultRows` |
| `POST /reasoning/query?dataset=<name>` | `200` with a bounded `SELECT` or `ASK` over the dataset's current snapshot ([Snapshot queries](#snapshot-queries)); `400` for anything else; `404` for a dataset that is not served; `413` over 1 MiB; `429` over `OPENMETADATA_REASONING_MAX_CONCURRENT_QUERIES`; `503` with `NOT_READY` before the first snapshot, or with `LIMIT_EXCEEDED` (`maxQuerySeconds` when the deadline passes, `maxQueryScannedQuads` when the query reads too many quads) |

Every refusal answers with the protocol's problem object, `{"code": ..., "message": ...}`, with
`subject` naming the limit for `LIMIT_EXCEEDED`. A request is validated against the schema and the
schemas it references, including the per-operation requirements (a `REFRESH` needs
`ruleBundleDigest` and its `rules`, and takes no `releaseRdf`; `CHECK` and `EXPLAIN` name exactly
one release in `ontologyReleases`, the uploaded RDF in `releaseRdf`, and their question), before
anything is stored. It may nest at most 64 levels deep. Every request lists its ontology releases
(`ontologyId`, `releaseId`, and the release's `contentDigest`, which the store only records); the
job's `input` records them with `releaseRdf`.

**Uploads.** A release is sent first, then a job names it. Uploads live under
`/fuseki/reasoning/inputs/<hex>`, addressed by the SHA-256 of their bytes: the body is streamed to a
temporary file while it is hashed, refused over `maxUploadBytes` or when the hash differs from the
path, and renamed into place, so a reader never sees a partial upload and the same bytes uploaded
again change nothing but the clock below. They count against `OPENMETADATA_REASONING_DISK_QUOTA` with
everything else under `/fuseki/reasoning`. An upload that cannot fit is refused with `503`
`LIMIT_EXCEEDED` (`maxDiskBytes`) before its body is read when the store is already at the quota or
the `Content-Length` would pass it; without one, or when others grew the data meanwhile, it is
refused and removed once received. At most 4 uploads are received at once; a fifth gets `429`
`LIMIT_EXCEEDED` (`maxConcurrentUploads`), the answer a full job queue gives, and retries. Neither
the disk measure nor a sweep delays job status reads.
An upload is kept while any queued or running job names it. Once none does, it is removed when
more than an hour has passed since it was last uploaded, so a second job over the same release needs
no second upload; uploads no job ever names go the same way. The removal runs when an upload
arrives, when a job ends and at server start, which also removes half-written uploads. The hour is
fixed, not a setting.

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
`EXPLAIN` runs `snapshot` (a governance fact is answered there, see
[Governance explanations](#governance-explanations)), then `capture`, `load` and `explain`; a
`REFRESH` runs `capture`, `load`, `classify` (one worker step per release), `rules`, `compact` and
`publish` ([Refresh](#refresh)). The outcome of a check or explanation is the protocol's answer:

| Result | Job |
|---|---|
| Consistent closure, `CONSISTENCY` | `SUCCEEDED`, `NOT_ENTAILED` |
| `SATISFIABILITY`, `SUBSUMPTION`, `ENTAILMENT`, or `EXPLAIN` | `SUCCEEDED`, `ENTAILED` or `NOT_ENTAILED` (`SATISFIABILITY` asks whether the class is a subclass of `owl:Nothing`) |
| Inconsistent closure | `SUCCEEDED`, `INCONSISTENT` |
| A limit, the job's time, or the worker's heap ran out | `SUCCEEDED`, `INCOMPLETE`, with the problem `LIMIT_EXCEEDED` naming the limit (`maxImports`, `maxClosureAxioms`, `maxIndividuals`, `maxExpressionDepth`, `maxDiskBytes`, `maxJobSeconds`) or `MEMORY_BUDGET_EXHAUSTED` |
| Closure outside OWL 2 DL | `FAILED`, `PROFILE_VIOLATION` |
| The upload is not held, has another size or hash than `releaseRdf` says, or does not hold exactly one `owl:Ontology` with `owl:versionIRI <urn:openmetadata:ontology-release:{releaseId}>` | `FAILED`, `INPUT_CHANGED`, `subject` the upload's digest, or the `ontologyId` for a wrong header |
| The release has an `owl:imports`, which nothing can satisfy because no other ontology is in the input | `FAILED`, `MISSING_IMPORT`, `subject` the imported IRI |
| An upload that is not RDF-Thrift | `FAILED`, `INVALID_REQUEST`, `subject` the upload's digest |
| A question naming an entity outside the closure | `FAILED`, `INVALID_REQUEST` |
| Cancelled | `CANCELLED` |

The job's `input.engines` names the engine build that answered. `result.json` in the job directory
holds the detail: the unsatisfiable classes (at most 100, with the full count) and the check's
axiom for a `CHECK`, the justifications for an `EXPLAIN`, and the closure's admission counts. An
`EXPLAIN` also gets `justificationSources`, parallel to `justifications`: for each axiom, the
`dct:source` members the release export attached to it (empty for an axiom without one), so an
explanation can name the members behind each statement. `capture.json` records the release's
ontology IRI, its version IRI and the spool's size. A question the schema admits but OWL cannot state, such as
an intersection without operands, is refused with `400` when the job is submitted.

Capabilities report every operation `AVAILABLE` with the limits the protocol requires of it
(`maxQueuedJobs`, `maxJobSeconds`, `maxDiskBytes`, `maxClosureAxioms`, `maxIndividuals`,
`maxExpressionDepth`, `maxImports` and `maxUploadBytes` for the jobs; `maxDerivedTriples`
(10,000,000) and `maxRulePasses` (64) for `REFRESH`; `maxResultRows` for `RESULTS` and `QUERY`, and
`maxQuerySeconds` for `QUERY`), the engine builds read from the worker jar's manifest plus
`governance-rules` (`jena-6.2.0`, SPARQL CONSTRUCT rules), and the OWL 2 DL constructs as coverage.
`NOT_READY` stays the transient answer (reasoning disabled, memory budget, disk unmeasurable, server
stopping, another worker running). An operation is `AVAILABLE` only while the store enforces every
limit it needs (OpenMetadata's
`ADR:2026-10-14-rdf-reasoning-capabilities-list-each-operation-and-the-limits-it-enforces`); when
the disk quota cannot be resolved, the job operations are reported `NOT_IMPLEMENTED` and readiness
`NOT_READY`, and without the worker's rule compiler (an image without the worker) so is `REFRESH`.
The admission limits are the worker's for `OPENMETADATA_REASONER_HEAP` and are written into every
step file, so the worker enforces exactly what capabilities report.

### Capture

OpenMetadata seals an ontology's members into an immutable release. Fuseki holds only each
ontology's active release, so a release under review, which a `CHECK` or `EXPLAIN` may name, is
sent as an upload instead of entering the serving dataset (OpenMetadata's
`ADR:2026-11-10-reasoning-jobs-name-ontology-releases-and-carry-their-rules`). The upload is the
release's export as RDF-Thrift, which keeps each statement's source member as an `owl:Axiom` with
`owl:annotatedSource`, `owl:annotatedProperty`, `owl:annotatedTarget` and `dct:source`. The capture
never reads the serving dataset. For the release a request names it:

1. finds the upload `releaseRdf.digest` names and checks it again: its size must be `byteSize`
   and its bytes must hash to the digest, as they did when they were uploaded, since a job may wait
   in the queue and a disk may corrupt a file. Anything else is `INPUT_CHANGED` with the digest as
   subject: upload again and resubmit;
2. parses the RDF-Thrift as a stream into one graph, `https://open-metadata.org/graph/ontology/{ontologyId}`
   (the name an active release has in the serving dataset), and writes N-Quads, gzip level 1, to
   `capture.nq.gz` in the job directory, renamed into place only when complete. Memory does not
   grow with the release;
3. requires exactly one `owl:Ontology`, named, whose `owl:versionIRI` is
   `urn:openmetadata:ontology-release:{releaseId}` of the release the request named. Anything else
   is `INPUT_CHANGED` with the `ontologyId` as subject: the bytes are not that release. Imports
   between ontologies are deferred, so no other ontology is in the input and any `owl:imports`
   cannot resolve; it is `MISSING_IMPORT` naming the import. `contentDigest` is not checked, since
   it is OpenMetadata's digest of the sealed manifest and says nothing about the export's bytes.

Every 4,096 quads the capture checks the triple bound (the worker's `maxClosureTriples`, which
protects `maxClosureAxioms` before parsing), the compressed size against what the disk quota leaves,
the job's deadline and cancellation; `maxImports` is 1. The working dataset the load builds is
checked against the quota again before anything reasons over it. Reaching a bound stops the copy and
removes the partial spool.

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

### Refresh

A `REFRESH` recomputes every deduction from asserted input and publishes it as a new snapshot of
the serving dataset it names (the plan's refresh lifecycle). The request lists every active
ontology release in `ontologyReleases` (possibly none) and carries the governance rules inline in
`rules`, with OpenMetadata's digest of them in `ruleBundleDigest`
(`ADR:2026-11-10-reasoning-jobs-name-ontology-releases-and-carry-their-rules`). The store records
the releases' `contentDigest`s and the rule digest with the snapshot's `input` and never checks or
recomputes either: they are OpenMetadata's, and what the store can check is the serving graph.

**Rules.** Disabled rules take no part. Every enabled rule is compiled with the `RULES` step's own
compiler when the job is submitted, so a rule OpenMetadata saves is never refused later, and one it
should not have saved is `400` before anything is queued: `RULE_FAILED` with the rule as subject
and the compiler's problems (at most 10) as message, or `INVALID_REQUEST` for a name used twice or a
body over 16,384 characters. `RuleCompilerVectorsTest` runs OpenMetadata's admission vectors
through the same entry point.

**Capture.** Inside one read transaction of the serving dataset the extension:

1. reads, for each listed release, the graph `https://open-metadata.org/graph/ontology/{ontologyId}`
   in which Fuseki holds an active release. It must hold exactly one named `owl:Ontology` whose
   `owl:versionIRI` is `urn:openmetadata:ontology-release:{releaseId}`: a missing graph, or a graph
   of another release because the ontology was activated again since the request was made, fails
   the job `INPUT_CHANGED` with the `ontologyId` as subject, and the next refresh sees the new
   release. An `owl:imports` is `MISSING_IMPORT`, as for a `CHECK`, since no other ontology is in
   the input. Each release is one closure with a `CLASSIFY` step of its own; at most 1,000;
2. streams the catalog: every quad whose predicate a rule can match, read predicate by predicate
   from TDB2's predicate-first index (a rule with a variable predicate or a negated property set
   reads everything), from every graph but the store's own (`urn:openmetadata:`) and the paths
   `/graph/ontology/` (every ontology graph: the listed releases are read in step 1, and a release
   that is not listed is no input), `/graph/staging/` (where OpenMetadata stages the chunks of a
   graph it replaces, which are never complete) and `/graph/inferred/` (the phase 0 materializer's
   output). Graph decisions are kept in an LRU of 4,096; the catalog may span 10,000 graphs and
   the rules 100,000 graphs in all.

The spool is checked against the disk quota and the deadline every 4,096 quads, and the server
holds one release's header, the graph names and nothing of the catalog. A refresh whose source
revision is older than the current snapshot's (same dataset generation, lower watermark) stops
before capturing with `SUPERSEDED`. A refresh with no releases still runs the rules over the
catalog.

**Reasoning.** `load` bulk-loads the spool; `classify` runs one `CLASSIFY` step per closure into
`urn:openmetadata:entailments:<release id>`; `rules` runs the rules to a fixed point
(`maxPasses` 64) reading the catalog graphs, the closures' graphs and their entailments, writing
each rule's conclusions to `urn:openmetadata:inferred:<rule>` and its witnesses to the file
`provenance` in the job directory; `compact` compacts the working dataset; `publish` makes the two
the dataset's current snapshot. The closures' entailments and the rules' new facts together may not
pass `maxDerivedTriples`: each `CLASSIFY` gets what is left as `maxOutputTriples`, and `RULES` gets
the rest as `maxNewFacts`; the spool of a pass gets what the disk quota leaves. A refresh publishes
complete results or none: a limit, the deadline, the worker's heap, a release outside OWL 2 DL or
an inconsistent release (`INCONSISTENT_ONTOLOGY`, subject the ontology) fails it, with the
protocol's limit names (`maxRulePasses`, `maxDerivedTriples`, `maxDiskBytes`, `maxJobSeconds`) as
subjects, and the previous snapshot stays current. A `SUCCEEDED` refresh has `snapshotId`, and
`input.engines` names the engines that computed it.

### Snapshots

Each serving dataset has a lineage of snapshots under `/fuseki/reasoning/snapshots/<dataset>/`:
one directory per snapshot with its compacted TDB2 database (`tdb/`), the provenance file of the
rules that derived it (`provenance`, absent when no rules ran) and `snapshot.json`, and
`current.json`, the pointer to the current one and its generation, a number that grows by one with
every publication. The database holds what queries read and nothing else.

- **Atomic.** `publish` moves the job's working dataset and its provenance file into a new snapshot
  directory (renames on the same volume, never copies), opens the database, writes the metadata,
  and only then replaces the pointer by an atomic rename and swaps the current snapshot under one
  lock. A reader sees the old snapshot
  or the new one, whole; a failure before the rename removes the new directory and leaves the old
  one current.
- **Compare-and-set.** The capture records the generation that was current; `publish` succeeds
  only if it still is, and never over a snapshot of the same dataset generation with a higher
  live-write watermark or one published by a job submitted later (jobs carry a submission
  sequence). Anything else fails `SUPERSEDED` and publishes nothing, so a late or older refresh can
  never replace a newer snapshot.
- **Leases and retention.** A query or explanation leases the current snapshot while it reads; its
  deadline bounds the lease. A replaced snapshot is closed and deleted when its last lease ends, so
  a snapshot in use is never deleted, and at most 4 replaced snapshots per dataset wait for readers
  (a fifth publication waits up to the query deadline for one to go). At startup everything but the
  current snapshot of each dataset is removed: replaced snapshots and unfinished publications. The
  snapshots count toward `OPENMETADATA_REASONING_DISK_QUOTA` with the jobs.
- **Isolation.** Snapshots are never registered as Fuseki datasets, and inferred results never go
  to the serving dataset: OpenMetadata's phase 0 graphs `{base}graph/inferred/*` are not read by a
  refresh and are OpenMetadata's to remove after cutover. The Fuseki JVM opens a snapshot only after
  the worker that wrote it has exited, lazily and with TDB2's small store parameters, so its caches
  stay small in the server heap.
- **Format.** `snapshot.json` names the snapshot's layout in `store.format`, now 2. Format 1 kept
  the witnesses in the graph `urn:openmetadata:provenance` of the database, which every query had
  to be kept from. A snapshot of any other format is removed at startup like one that is not
  current: the dataset has no snapshot, and its generation starts again at 1, with its next
  refresh.

### Snapshot queries

`POST /reasoning/query?dataset=<name>` with `{"query": "<SELECT or ASK>", "maxRows": <optional>,
"timeoutSeconds": <optional>}` runs over the dataset's current snapshot: its default graph is the
union of every graph of the snapshot's database, and `GRAPH` sees the same graphs. The witnesses
are not in the database, so no query reaches them. `FROM`, `FROM NAMED`, `SERVICE`, other query
forms and texts over 65,536 characters are refused.
The answer is `{"snapshot": <rdfReasoningSnapshot without freshness>, "generation": <n>, "rows":
<n>, "truncated": <bool>, "results": <SPARQL 1.1 JSON results>}`: at most `maxResultRows` rows
(or fewer if asked) and 8 MiB of terms, with `truncated` when there were more. Freshness is
OpenMetadata's to compute from the snapshot's input.

Rows and bytes bound the answer, not the work before its first row, so a query is also bounded by
what it reads: past `OPENMETADATA_REASONING_MAX_QUERY_SCANNED_QUADS` quads (counted at the store)
it stops with `LIMIT_EXCEEDED` and the subject `maxQueryScannedQuads`. The default, one million, is
about one and a half times the 0.68 million quads of a 20,000-table snapshot: a sort, `COUNT(*)` or
grouping over a whole snapshot stops from about 30,000 tables, while a query that follows bound
patterns to at most a page of rows does not at any size. A `SELECT` with `ORDER BY` and no `LIMIT` below `maxRows + 1` is run with
`LIMIT maxRows + 1`, so ARQ keeps a heap of that size instead of sorting the whole result; the answer
and its `truncated` flag are the same. Sorts and `DISTINCT` hold at most 100,000 rows in memory and
spill to files in the JVM's `java.io.tmpdir`, which are deleted when the query ends; `GROUP BY` does
not spill, and the scan budget is its bound. The query runs with ARQ's general engine over a
read-only view: handing it TDB2's own engine would bypass the view, and with it the scan budget
and the union default graph.

### Results

`GET /reasoning/results/<job id>` answers `{"job": <job>, "result": <result.json>, "page":
{"items", "offset", "limit", "total"}}` for a finished job: the closures of a refresh (with each
one's entailment graph, engine and unsatisfiable classes, plus the rule counts and the snapshot),
the unsatisfiable classes of a check, or the justifications of an explanation are paged, up to
`maxResultRows` per page.

### Governance explanations

An `EXPLAIN` whose statement is a fact the dataset's current snapshot holds in a rule's output
graph is answered `ENTAILED` from that snapshot's provenance file (`result.json` has `"kind":
"GOVERNANCE"`, the snapshot and the explanation): the rule and pass that first derived the fact,
its premises, each explained in turn down to `ASSERTED` facts, property-path premises as one
shortest chain, bounded at depth 16, 1,000 facts (or `maxResultRows`), 256-triple chains and
100,000 visited nodes, with `truncated` where a bound stopped it. Any other statement goes on to
the OWL explanation. The explanation runs in the Fuseki JVM, since TDB2 locks a database to the one
process that opens it and serving holds the snapshot open: the extension loads the worker jar's
`ServerBridge`, and nothing else, in a class loader of its own whose parent supplies Jena, so the
rule code (the same compiler and `Explanations` the worker runs) never reaches the OWL API or any
other worker dependency (`RuleCodeIsolationIT`). Admission uses the same bridge.

**Measured** on an Apple M-series laptop, Java 21, worker heap 1 GiB (`RefreshPerformanceIT`, run
with `-Dreasoning.performance=true`; `-Dreasoning.performance.tables=` sets the size), with the
starter PII, schema-tag and domain rules and a release of 1,000 classes, while a reader and a
writer used the serving dataset:

| Catalog | Captured | Derived | Job | Steps (capture, load, classify, rules, compact) | Worker heap after GC | Server heap after GC | Snapshot | Peak reasoning disk | Slowest serving read / write, before and during |
|---|---|---|---|---|---|---|---|---|---|
| 5,000 tables, 137,614 triples | 85,612 catalog triples, 0.43 MB spool | 81,900 facts, 21 passes | 20.6 s | 0.5, 1.9, 1.1, 10.7, 6.3 s | 45 MiB at most | 41 MiB | 416 MB | 0.99 GB | 60 / 128 ms before, 121 / 242 ms during |
| 20,000 tables, 544,444 triples | 342,442, 1.65 MB | 327,600, 21 passes | 52.5 s | 1.6, 3.3, 1.1, 25.1, 21.4 s | 54 MiB at most | 76 MiB | 1.14 GB | 2.91 GB | 62 / 129 ms before, 113 / 242 ms during |

The column descriptions no rule reads were never captured. Disk, not memory, is what a refresh
costs: a snapshot holds the captured input, the derived facts and one witness per fact in six TDB2
quad indexes, and a refresh needs the working dataset and its compacted copy at once. A snapshot
query for the 4,200 PII columns of the larger catalog took 157 ms.

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
| `O_f` | Fuseki off-heap | Its direct-buffer limit (`-XX:MaxDirectMemorySize`, 256 MiB unless `JVM_ARGS` sets it; 0 means the heap) plus 768 MiB, or 1.25 GiB from an 8 GiB heap: 1 GiB and 1.5 GiB by default |
| `H_w` | Worker heap | `OPENMETADATA_REASONER_HEAP` |
| `O_w` | Worker off-heap: metaspace, code cache and direct memory are capped at 256, 128 and 64 MiB | `OPENMETADATA_REASONER_OFF_HEAP` |
| `C_min` | Page cache kept for serving | `OPENMETADATA_REASONER_PAGE_CACHE_FLOOR`; 50% of `/fuseki/databases` by default |

The limit is read from cgroup v2 `memory.max`, else cgroup v1, else physical memory when the
container has no limit.

`O_f` and `O_w` were measured with Native Memory Tracking (committed memory outside the heap) on
Apple M4 Max hardware, in a `linux/arm64` container limited to 4 CPUs and 16 GiB:

- **Fuseki**, 4 GiB heap, while OpenMetadata rebuilt a 20,000-table, 200,000-edge catalog (2.8
  million triples) twice and queried it: direct buffers reached 1.5 GiB without a limit, because
  Fuseki allocates about 5 MiB of them per HTTP request and frees them only when a collection finds
  their owners; everything else outside the heap stayed under 300 MiB. The entrypoint therefore caps
  direct buffers, and the JVM collects when it reaches the cap.
- **Worker**, 2 GiB heap: at most 200 MiB outside the heap, on every conformance fixture and on
  catalog-shaped closures of 10,000 to 50,000 individuals. The 512 MiB default leaves room for the
  capped regions it was sized from.

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

Any step file runs this way, a `RULES` step included. `readiness` prints the budget as JSON, with `status` (`READY`, `NOT_READY` or `DISABLED`),
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

Fuseki 6.2.0 bundles Apache Shiro 2.2.1 and Micrometer 1.17.0 inside `fuseki-server.jar`, and both
carry published CVEs. The `server` stage of the Dockerfile replaces them with the releases listed in
`server-jar/replacements.sha256`, downloaded from Maven Central and checked by SHA-256; the rest of
Apache's jar is unchanged. Fuseki's own classes call the same Shiro and Micrometer methods when
compiled against the new releases, and Fuseki's test suite gives the same results on them. Drop the
stage when a Fuseki release ships fixed versions of both.

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

In `reasoner/`, `RefreshJobsIT` runs `REFRESH`, `QUERY`, `RESULTS` and governance `EXPLAIN` end to
end with the packaged worker: a refresh publishes a snapshot a query reads; removing a premise and
refreshing again retracts what it supported and advances the generation while a leased older
snapshot survives until its reader is done; an older refresh fails `SUPERSEDED`; the snapshot serves
after a restart; a rule that cannot be admitted is refused at submission, a release that is no longer the
active one fails `INPUT_CHANGED`, and an inconsistent release publishes nothing; an unlisted
ontology graph and a staging graph never reach the rules.
`RuleCodeIsolationIT` loads the packaged worker jar as the server does, behind a parent that hides
the worker's dependencies. The smoke test refreshes over `test/refresh-catalog.trig` in the image, queries the snapshot, explains a governance fact,
retracts it, refreshes out of order and restarts.

`mvn verify` in `extension/` runs the job operations against stand-in worker processes: queueing,
cancellation, restart recovery and retention on real job directories, and the HTTP operations on a
real Fuseki server with Shiro, uploads included. It checks every request example, job and
capabilities document against OpenMetadata's schemas, uploads against their size limit, digest,
quota and retention, and captures real RDF-Thrift releases, accepting only the one a request names.
In `reasoner/`, `OntologyJobsIT` runs `CHECK` and `EXPLAIN` jobs end to end with the packaged
worker: a release uploaded as OpenMetadata sends it, consistent, with an unsatisfiable class, made
inconsistent by an assertion, a release that is not the one named, an import, and a release over its
limit. The smoke test uploads `test/check-release.rdf-thrift` to the image and runs a `CHECK` and an
`EXPLAIN` over it, with the sources of the explanation's axioms, submits, reads and cancels a job,
and restarts the container to check that the job and its request ID survive.

## Publishing

Images are published only from `release/<fuseki-version>` branches, never from `main`. A release branch
is cut from the matching upstream tag (`jena-6.2.0` for `release/6.2.0`), and its name must match the
`FUSEKI_VERSION` in the Dockerfile.

A publish (a push to the release branch, or a manual run of the workflow with the branch as input)
runs in three jobs of `.github/workflows/openmetadata-fuseki-image.yml`:

1. **Build and push.** After the extension, reasoner and local smoke tests pass, it pushes one
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
