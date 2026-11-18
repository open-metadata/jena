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

import java.util.Set;
import java.util.TreeSet;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpAssign;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVisitorBase;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Path2;
import org.apache.jena.sparql.path.P_ReverseLink;
import org.apache.jena.sparql.path.Path;

/**
 * The predicates a rule bundle can match, read from each rule's SPARQL algebra, so a REFRESH
 * captures only those triples of the catalog (the plan's predicate-scoped capture). A pattern with
 * a variable predicate, or a negated property set, can match any predicate; the capture then reads
 * every catalog triple. EXISTS conditions are read too, since their patterns are matched against
 * the same graphs.
 */
public final class RulePredicates {
  /** The base IRI OpenMetadata and the store's rule compiler parse rule bodies against. */
  static final String BASE = "http://openmetadata.invalid/rules/";

  private final Set<String> predicates = new TreeSet<>();
  private boolean any;

  private RulePredicates() {}

  /** The predicates of these rule bodies; a body that does not parse matches nothing here. */
  public static RulePredicates of(final Iterable<String> bodies) {
    final RulePredicates result = new RulePredicates();
    for (final String body : bodies) {
      final Query query;
      try {
        query = QueryFactory.create(body, BASE, Syntax.syntaxSPARQL_11);
      } catch (final RuntimeException e) {
        // Admission refuses such a rule before anything is captured.
        continue;
      }
      if (query.getQueryPattern() != null) {
        result.walk(Algebra.compile(query.getQueryPattern()));
      }
    }
    return result;
  }

  /** Whether some rule can match any predicate, so the capture must read every triple. */
  public boolean any() {
    return any;
  }

  /** The predicate IRIs the rules name, in IRI order; meaningless when {@link #any()}. */
  public Set<String> predicates() {
    return Set.copyOf(predicates);
  }

  private void walk(final Op op) {
    OpWalker.walk(
        op,
        new OpVisitorBase() {
          @Override
          public void visit(final OpBGP bgp) {
            bgp.getPattern().forEach(RulePredicates.this::triple);
          }

          @Override
          public void visit(final OpTriple triple) {
            triple(triple.getTriple());
          }

          @Override
          public void visit(final OpQuadPattern quads) {
            quads.getPattern().forEach(quad -> triple(quad.asTriple()));
          }

          @Override
          public void visit(final OpPath path) {
            final Node predicate = path.getTriplePath().getPredicate();
            if (predicate != null) {
              node(predicate);
            } else {
              path(path.getTriplePath().getPath());
            }
          }

          @Override
          public void visit(final OpFilter filter) {
            expressions(filter.getExprs());
          }

          @Override
          public void visit(final OpLeftJoin join) {
            expressions(join.getExprs());
          }

          @Override
          public void visit(final OpExtend extend) {
            extend.getVarExprList().getExprs().values().forEach(RulePredicates.this::expression);
          }

          @Override
          public void visit(final OpAssign assign) {
            assign.getVarExprList().getExprs().values().forEach(RulePredicates.this::expression);
          }
        });
  }

  private void expressions(final ExprList expressions) {
    if (expressions != null) {
      expressions.forEach(this::expression);
    }
  }

  private void expression(final Expr expression) {
    Walker.walk(
        expression,
        new ExprVisitorBase() {
          @Override
          public void visit(final ExprFunctionOp function) {
            if (function.getGraphPattern() != null) {
              walk(function.getGraphPattern());
            }
          }
        });
  }

  private void triple(final Triple triple) {
    node(triple.getPredicate());
  }

  private void node(final Node predicate) {
    if (predicate.isURI()) {
      predicates.add(predicate.getURI());
    } else {
      any = true;
    }
  }

  /** Links name a predicate; a negated property set matches every other one. */
  private void path(final Path path) {
    if (path instanceof P_Link link) {
      node(link.getNode());
    } else if (path instanceof P_ReverseLink link) {
      node(link.getNode());
    } else if (path instanceof P_Path1 unary) {
      path(unary.getSubPath());
    } else if (path instanceof P_Path2 binary) {
      path(binary.getLeft());
      path(binary.getRight());
    } else {
      any = true;
    }
  }
}
