/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.fuseki.reasoning.jobs;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.resultset.ResultSetLang;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSet;
import org.apache.jena.sparql.exec.RowSetStream;
import org.apache.jena.sparql.resultset.ResultsWriter;
import org.apache.jena.sparql.util.Context;

/**
 * The QUERY operation: one SELECT or ASK over a dataset's current snapshot, its asserted and
 * derived graphs together and never its provenance. The query leases the snapshot for as long as it
 * runs; it has a deadline (maxQuerySeconds) and returns at most maxResultRows rows and {@value
 * #MAX_RESULT_BYTES} bytes of terms, held in the Fuseki heap only as that bounded answer, and says
 * when it was cut short. At most maxConcurrentQueries run at once; the rest are refused at once,
 * not queued, so a burst never holds request threads.
 */
public final class SnapshotQueries {
  /** The query text of a request; the request itself is at most {@value #MAX_REQUEST_BYTES}. */
  public static final int MAX_QUERY_CHARACTERS = 65_536;

  public static final int MAX_REQUEST_BYTES = 1 << 20;

  /** Bytes of terms one answer holds, whatever its row count. */
  static final long MAX_RESULT_BYTES = 8L << 20;

  private final SnapshotStore snapshots;
  private final JobSettings settings;
  private final Semaphore permits;

  /** An answer, ready to send. */
  public record Answer(JsonObject envelope, byte[] results) {
    /** The whole response body: the envelope with the SPARQL JSON results under "results". */
    public byte[] body() {
      final String head = JSON.toString(envelope);
      final String open = head.substring(0, head.lastIndexOf('}')).stripTrailing();
      final ByteArrayOutputStream out = new ByteArrayOutputStream(results.length + head.length());
      out.writeBytes(open.getBytes(StandardCharsets.UTF_8));
      out.writeBytes(",\n  \"results\": ".getBytes(StandardCharsets.UTF_8));
      out.writeBytes(results);
      out.writeBytes("\n}\n".getBytes(StandardCharsets.UTF_8));
      return out.toByteArray();
    }
  }

  public SnapshotQueries(final SnapshotStore snapshots, final JobSettings settings) {
    this.snapshots = snapshots;
    this.settings = settings;
    this.permits = new Semaphore(settings.maxConcurrentQueries());
  }

  /** Runs a query request against {@code dataset}'s current snapshot. */
  public Answer run(final String dataset, final byte[] body) throws JobService.Refused {
    final Request request = parse(body);
    if (!permits.tryAcquire()) {
      throw new JobService.Refused(
          429,
          Problem.of(
              Problem.Code.LIMIT_EXCEEDED,
              settings.maxConcurrentQueries()
                  + " snapshot queries are already running ("
                  + JobSettings.MAX_CONCURRENT_QUERIES
                  + ")"));
    }
    try {
      final Optional<SnapshotStore.Lease> leased = snapshots.lease(dataset);
      if (leased.isEmpty()) {
        throw new JobService.Refused(
            503,
            Problem.of(
                Problem.Code.NOT_READY,
                "No reasoning snapshot of '" + dataset + "' has been published; run a REFRESH"));
      }
      try (SnapshotStore.Lease lease = leased.get()) {
        return execute(lease, request);
      }
    } finally {
      permits.release();
    }
  }

  private record Request(Query query, int maxRows, long timeoutMillis) {}

  private Request parse(final byte[] body) throws JobService.Refused {
    final JsonObject json;
    try {
      final JsonValue value = JSON.parseAny(new String(body, StandardCharsets.UTF_8));
      if (!value.isObject()) {
        throw invalid("A query request is a JSON object");
      }
      json = value.getAsObject();
    } catch (final JsonException e) {
      throw invalid("A query request is a JSON object: " + e.getMessage());
    }
    for (final String key : json.keys()) {
      if (!List.of("query", "maxRows", "timeoutSeconds").contains(key)) {
        throw invalid("Unknown query request field '" + key + "'");
      }
    }
    final JsonValue text = json.get("query");
    if (text == null || !text.isString() || text.getAsString().value().isBlank()) {
      throw invalid("A query request needs the query text in 'query'");
    }
    if (text.getAsString().value().length() > MAX_QUERY_CHARACTERS) {
      throw invalid("A query has at most " + MAX_QUERY_CHARACTERS + " characters");
    }
    final int maxRows =
        bounded(json, "maxRows", settings.maxResultRows(), JobSettings.MAX_RESULT_ROWS);
    final int seconds =
        bounded(json, "timeoutSeconds", settings.maxQuerySeconds(), JobSettings.MAX_QUERY_SECONDS);
    final Query query;
    try {
      query = QueryFactory.create(text.getAsString().value(), Syntax.syntaxSPARQL_11);
    } catch (final QueryException e) {
      throw invalid("The query does not parse: " + e.getMessage());
    }
    if (!query.isSelectType() && !query.isAskType()) {
      throw invalid("A snapshot query is a SELECT or an ASK");
    }
    if (query.hasDatasetDescription()) {
      throw invalid(
          "A snapshot query reads the snapshot's graphs; FROM and FROM NAMED are not allowed");
    }
    final boolean[] service = {false};
    OpWalker.walk(
        Algebra.compile(query),
        new OpVisitorBase() {
          @Override
          public void visit(final OpService op) {
            service[0] = true;
          }
        });
    if (service[0]) {
      throw invalid("A snapshot query cannot call SERVICE");
    }
    return new Request(query, maxRows, TimeUnit.SECONDS.toMillis(seconds));
  }

  private Answer execute(final SnapshotStore.Lease lease, final Request request)
      throws JobService.Refused {
    final DatasetGraph view = lease.view();
    final Context context = ARQ.getContext().copy();
    context.set(ARQ.httpServiceAllowed, false);
    view.begin(TxnType.READ);
    try (QueryExec exec =
        QueryExec.dataset(view)
            .query(request.query())
            .context(context)
            .timeout(request.timeoutMillis(), TimeUnit.MILLISECONDS)
            .build()) {
      final ByteArrayOutputStream results = new ByteArrayOutputStream();
      boolean truncated = false;
      long rows = 0;
      if (request.query().isAskType()) {
        ResultsWriter.create().lang(ResultSetLang.RS_JSON).build().write(results, exec.ask());
      } else {
        final RowSet rowSet = exec.select();
        final List<Var> vars = rowSet.getResultVars();
        final List<Binding> bindings = new ArrayList<>();
        long bytes = 0;
        while (rowSet.hasNext()) {
          if (bindings.size() >= request.maxRows()) {
            truncated = true;
            break;
          }
          final Binding binding = rowSet.next();
          bytes += size(binding);
          if (bytes > MAX_RESULT_BYTES) {
            truncated = true;
            break;
          }
          bindings.add(binding);
        }
        rows = bindings.size();
        ResultsWriter.create()
            .lang(ResultSetLang.RS_JSON)
            .build()
            .write(results, RowSetStream.create(vars, bindings.iterator()));
      }
      final JsonObject envelope = new JsonObject();
      envelope.put("snapshot", lease.description());
      envelope.put("generation", lease.generation());
      envelope.put("rows", rows);
      envelope.put("truncated", truncated);
      return new Answer(envelope, results.toByteArray());
    } catch (final QueryCancelledException e) {
      throw new JobService.Refused(
          503,
          Problem.of(
                  Problem.Code.LIMIT_EXCEEDED,
                  "The query did not finish within "
                      + TimeUnit.MILLISECONDS.toSeconds(request.timeoutMillis())
                      + " s")
              .withSubject("maxQuerySeconds"));
    } catch (final QueryException e) {
      throw invalid("The query cannot be evaluated: " + e.getMessage());
    } finally {
      view.end();
    }
  }

  private static long size(final Binding binding) {
    long size = 0;
    for (final Iterator<Var> vars = binding.vars(); vars.hasNext(); ) {
      final Node node = binding.get(vars.next());
      size += node == null ? 0 : node.toString().length() + 16;
    }
    return size;
  }

  private static int bounded(
      final JsonObject json, final String key, final int max, final String setting)
      throws JobService.Refused {
    final JsonValue value = json.get(key);
    if (value == null) {
      return max;
    }
    if (!value.isNumber()) {
      throw invalid(key + " must be a positive integer");
    }
    final long number;
    try {
      number = new BigDecimal(value.getAsNumber().value().toString()).longValueExact();
    } catch (final ArithmeticException e) {
      throw invalid(key + " must be a positive integer");
    }
    if (number < 1 || number > max) {
      throw invalid(key + " must be between 1 and " + max + " (" + setting + ")");
    }
    return (int) number;
  }

  private static JobService.Refused invalid(final String message) {
    return new JobService.Refused(400, Problem.of(Problem.Code.INVALID_REQUEST, message));
  }
}
