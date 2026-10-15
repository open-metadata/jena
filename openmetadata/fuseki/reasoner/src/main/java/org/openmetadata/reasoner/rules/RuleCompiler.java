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
package org.openmetadata.reasoner.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryException;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.op.OpAssign;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpConditional;
import org.apache.jena.sparql.algebra.op.OpDatasetNames;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpExtendAssign;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGraph;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpLateral;
import org.apache.jena.sparql.algebra.op.OpLeftJoin;
import org.apache.jena.sparql.algebra.op.OpMinus;
import org.apache.jena.sparql.algebra.op.OpModifier;
import org.apache.jena.sparql.algebra.op.OpNull;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpProcedure;
import org.apache.jena.sparql.algebra.op.OpPropFunc;
import org.apache.jena.sparql.algebra.op.OpQuad;
import org.apache.jena.sparql.algebra.op.OpQuadBlock;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpSequence;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.algebra.op.OpTopN;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.E_Bound;
import org.apache.jena.sparql.expr.E_Call;
import org.apache.jena.sparql.expr.E_Exists;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.E_LogicalAnd;
import org.apache.jena.sparql.expr.E_NotExists;
import org.apache.jena.sparql.expr.E_Now;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.Unstable;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_NegPropSet;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Path2;
import org.apache.jena.sparql.path.P_ReverseLink;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sparql.util.Context;
import org.openmetadata.reasoner.Problem;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.rules.CompiledRule.Atom;
import org.openmetadata.reasoner.rules.CompiledRule.Branch;
import org.openmetadata.reasoner.rules.CompiledRule.Condition;

/**
 * Admits a rule only if repeating it until nothing new is derived terminates and does not depend on
 * rule order, checked on its SPARQL algebra, independently of OpenMetadata's own check of the same
 * subset (FixedPointRuleCheck): a positive, finite CONSTRUCT. Negation (OPTIONAL, MINUS, NOT
 * EXISTS, BOUND, EXISTS anywhere but as a condition the whole FILTER requires) could retract a fact
 * another pass derived; minted terms (template blank nodes, template variables only computed by
 * BIND, nondeterministic functions) would grow forever; subqueries can aggregate or limit; SERVICE
 * reaches outside the dataset. This worker is stricter than OpenMetadata in three ways, each a
 * trust-boundary choice: rules read the union of the graphs the step selects, so GRAPH patterns and
 * FROM are refused; only SPARQL's own functions and XSD casts may be called, never extension
 * functions or property functions, which run code by IRI; and every rule is bounded in size.
 */
final class RuleCompiler {
  /** The body a rule may have; OpenMetadata's bundles keep each rule to a few KiB. */
  static final int MAX_BODY_CHARS = 16_384;

  /** Template triples a rule may have. */
  static final int MAX_TEMPLATE_TRIPLES = 32;

  /** Nesting depth of the rule's algebra and expressions. */
  static final int MAX_DEPTH = 32;

  /** Relative IRIs resolve against this, never against the worker's directory. */
  static final String BASE = "http://openmetadata.invalid/rules/";

  private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
  private static final String FN = "http://www.w3.org/2005/xpath-functions#";
  private static final Set<String> FN_NONDETERMINISTIC =
      Set.of(
          FN + "current-dateTime",
          FN + "current-date",
          FN + "current-time",
          FN + "implicit-timezone");

  private RuleCompiler() {}

  /** The compiled rule, or the problems that refuse it, each naming the rule. */
  record Result(CompiledRule rule, List<Problem> problems) {}

  static Result compile(
      final int index, final StepFile.Rule rule, final long maxPatterns, final Context context) {
    final List<Problem> problems = new ArrayList<>();
    final String name = rule.name();
    if (!"CONSTRUCT".equals(rule.ruleType())) {
      problems.add(
          problem(
              name,
              "RULE_TYPE",
              "ruleType " + rule.ruleType() + " is not supported; only CONSTRUCT rules run"));
      return new Result(null, problems);
    }
    if (rule.body().length() > MAX_BODY_CHARS) {
      problems.add(
          problem(name, "RULE_TOO_LARGE", "the body is longer than " + MAX_BODY_CHARS + " chars"));
      return new Result(null, problems);
    }
    final Query query;
    try {
      query = QueryFactory.create(rule.body(), BASE, Syntax.syntaxSPARQL_11);
    } catch (final QueryException e) {
      problems.add(problem(name, "RULE_PARSE", "the body is not SPARQL 1.1: " + e.getMessage()));
      return new Result(null, problems);
    }
    if (!query.isConstructType()) {
      problems.add(
          problem(name, "RULE_NOT_CONSTRUCT", "the body is a " + query.queryType() + " query"));
      return new Result(null, problems);
    }
    if (query.hasDatasetDescription()) {
      unsupported(problems, name, "FROM and FROM NAMED; rules read the graphs the step selects");
    }
    if (query.hasLimit() || query.hasOffset() || query.hasOrderBy()) {
      unsupported(problems, name, "LIMIT, OFFSET and ORDER BY; a pass must see every solution");
    }
    if (query.hasGroupBy() || query.hasHaving() || query.hasAggregators() || query.hasValues()) {
      unsupported(problems, name, "GROUP BY, HAVING, aggregates and a trailing VALUES");
    }
    final List<Triple> template = query.getConstructTemplate().getTriples();
    if (template.isEmpty()) {
      problems.add(problem(name, "RULE_TEMPLATE", "the CONSTRUCT template is empty"));
    } else if (template.size() > MAX_TEMPLATE_TRIPLES) {
      problems.add(
          problem(
              name,
              "RULE_TOO_LARGE",
              "the template has more than " + MAX_TEMPLATE_TRIPLES + " triples"));
    }
    if (query.getQueryPattern() == null) {
      problems.add(problem(name, "RULE_EMPTY", "the WHERE pattern is empty"));
      return new Result(null, problems);
    }
    final Walk walk = new Walk(name, problems, context);
    final Op pattern = walk.prepare(Algebra.compile(query.getQueryPattern()), 1, List.of());
    walk.finish();
    if (!problems.isEmpty()) {
      // A refused operator was not walked into, so its atoms are missing; the checks below would
      // only report that again.
      return new Result(null, problems);
    }
    if (walk.atoms.isEmpty() && !walk.hasValues) {
      problems.add(problem(name, "RULE_EMPTY", "the WHERE pattern matches nothing in the data"));
    }
    if (walk.patterns > maxPatterns) {
      problems.add(
          problem(
              name,
              "RULE_TOO_LARGE",
              "the body has "
                  + walk.patterns
                  + " triple and path patterns, over the limit of "
                  + maxPatterns
                  + " (maxRulePatterns)"));
    }
    checkTemplate(name, template, walk.boundVars, problems);
    if (!problems.isEmpty()) {
      return new Result(null, problems);
    }
    final List<Var> witnessVars =
        OpVars.visibleVars(pattern).stream().sorted(Comparator.comparing(Var::getVarName)).toList();
    return new Result(
        new CompiledRule(
            index,
            name,
            rule.body(),
            sha256(rule.body()),
            NodeFactory.createURI(rule.output()),
            pattern,
            List.copyOf(template),
            List.copyOf(walk.atoms),
            List.copyOf(walk.conditions),
            walk.ranges,
            Set.copyOf(walk.opaque),
            witnessVars),
        problems);
  }

  private static void checkTemplate(
      final String name,
      final List<Triple> template,
      final Set<Var> bound,
      final List<Problem> problems) {
    final Set<String> errors = new LinkedHashSet<>();
    for (final Triple triple : template) {
      final Node subject = triple.getSubject();
      final Node predicate = triple.getPredicate();
      final Node object = triple.getObject();
      for (final Node node : List.of(subject, predicate, object)) {
        if (node.isBlank()) {
          errors.add("the template must not contain blank nodes; each pass would create new nodes");
        } else if (node.isTripleTerm()) {
          errors.add("the template must not contain triple terms");
        } else if (node.isVariable() && !bound.contains(Var.alloc(node))) {
          errors.add(
              ("template variable ?%s must be bound by a triple pattern or VALUES; computed values"
                      + " would let the rule create new terms on every pass")
                  .formatted(Var.alloc(node).getVarName()));
        }
      }
      if (subject.isLiteral()) {
        errors.add("a template subject must not be a literal");
      }
      if (!predicate.isURI() && !predicate.isVariable()) {
        errors.add("a template predicate must be an IRI or a variable");
      }
    }
    errors.forEach(error -> problems.add(problem(name, "RULE_TEMPLATE", error)));
  }

  private static void unsupported(
      final List<Problem> problems, final String name, final String construct) {
    problems.add(problem(name, "RULE_UNSUPPORTED", construct + " are not allowed"));
  }

  static Problem problem(final String rule, final String code, final String message) {
    return new Problem(code, "rule '" + rule + "': " + message);
  }

  static String sha256(final String text) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * One pass over the algebra: checks every operator and expression, marks UNION branches, numbers
   * the atoms outside EXISTS and records the operator ranges the variants are cut by.
   */
  private static final class Walk {
    private final String name;
    private final Set<String> errors = new LinkedHashSet<>();
    private final List<Problem> problems;
    private final PropertyFunctionRegistry propertyFunctions;
    private final List<Atom> atoms = new ArrayList<>();
    private final List<Condition> conditions = new ArrayList<>();
    private final Map<Op, int[]> ranges = CompiledRule.identityMap();
    private final Set<Node> opaque = new HashSet<>();
    private final Set<Var> boundVars = new HashSet<>();
    private int markers;
    private int patterns;
    private boolean hasValues;
    private boolean tooDeep;

    Walk(final String name, final List<Problem> problems, final Context context) {
      this.name = name;
      this.problems = problems;
      this.propertyFunctions = PropertyFunctionRegistry.chooseRegistry(context);
    }

    /** The operator with its union branches marked, its atoms numbered from {@code atoms}. */
    Op prepare(final Op op, final int depth, final List<Branch> branches) {
      if (!checkDepth(depth)) {
        return op;
      }
      final int from = atoms.size();
      final Op prepared = prepareOperator(op, depth, branches);
      ranges.put(prepared, new int[] {from, atoms.size()});
      return prepared;
    }

    private Op prepareOperator(final Op op, final int depth, final List<Branch> branches) {
      if (op instanceof OpBGP bgp) {
        bgp.getPattern().getList().forEach(triple -> atom(triple, null, branches));
        return op;
      }
      if (op instanceof OpTriple triple) {
        atom(triple.getTriple(), null, branches);
        return op;
      }
      if (op instanceof OpPath path) {
        atom(null, path.getTriplePath(), branches);
        return op;
      }
      if (op instanceof OpJoin join) {
        return OpJoin.create(
            prepare(join.getLeft(), depth + 1, branches),
            prepare(join.getRight(), depth + 1, branches));
      }
      if (op instanceof OpSequence sequence) {
        final OpSequence copy = OpSequence.create();
        sequence.getElements().forEach(element -> copy.add(prepare(element, depth + 1, branches)));
        return copy;
      }
      if (op instanceof OpUnion union) {
        final Var marker = Var.alloc("~union" + markers++);
        return OpUnion.create(
            side(union.getLeft(), marker, 0, depth, branches),
            side(union.getRight(), marker, 1, depth, branches));
      }
      if (op instanceof OpFilter filter) {
        filter.getExprs().forEach(expr -> condition(expr, depth + 1, branches));
        return OpFilter.filterBy(
            filter.getExprs(), prepare(filter.getSubOp(), depth + 1, branches));
      }
      if (op instanceof OpExtend || op instanceof OpAssign) {
        final OpExtendAssign extend = (OpExtendAssign) op;
        extend.getVarExprList().forEachExpr((var, expr) -> expression(expr, depth + 1));
        final Op sub = prepare(extend.getSubOp(), depth + 1, branches);
        return op instanceof OpExtend
            ? OpExtend.create(sub, extend.getVarExprList())
            : OpAssign.create(sub, extend.getVarExprList());
      }
      if (op instanceof OpTable table) {
        if (!table.isJoinIdentity()) {
          hasValues = true;
          boundVars.addAll(table.getTable().getVars());
        }
        return op;
      }
      if (op instanceof OpNull) {
        return op;
      }
      refuse(op);
      return op;
    }

    /** A union branch, extended with its marker: 0 for the left side, 1 for the right. */
    private Op side(
        final Op op,
        final Var marker,
        final int side,
        final int depth,
        final List<Branch> branches) {
      final NodeValue value = NodeValue.makeInteger(side);
      final List<Branch> inner = new ArrayList<>(branches);
      inner.add(new Branch(marker, value.asNode()));
      final int from = atoms.size();
      final Op marked = OpExtend.create(prepare(op, depth + 1, List.copyOf(inner)), marker, value);
      ranges.put(marked, new int[] {from, atoms.size()});
      return marked;
    }

    private void atom(final Triple triple, final TriplePath path, final List<Branch> branches) {
      patterns++;
      if (triple != null) {
        predicateFunction(triple.getPredicate());
        bind(triple.getSubject());
        bind(triple.getPredicate());
        bind(triple.getObject());
        atoms.add(new Atom(atoms.size(), triple, null, branches));
      } else {
        bind(path.getSubject());
        bind(path.getObject());
        pathPredicates(path.getPath());
        atoms.add(new Atom(atoms.size(), null, path, branches));
      }
    }

    private void bind(final Node node) {
      if (node.isVariable()) {
        boundVars.add(Var.alloc(node));
      }
    }

    /** Collects the predicates a path can follow; they make the rule's paths opaque. */
    private void pathPredicates(final Path path) {
      if (path instanceof P_Link link) {
        predicateFunction(link.getNode());
        opaque.add(link.getNode());
      } else if (path instanceof P_ReverseLink link) {
        predicateFunction(link.getNode());
        opaque.add(link.getNode());
      } else if (path instanceof P_NegPropSet) {
        opaque.add(CompiledRule.ANY);
      } else if (path instanceof P_Path1 unary) {
        pathPredicates(unary.getSubPath());
      } else if (path instanceof P_Path2 binary) {
        pathPredicates(binary.getLeft());
        pathPredicates(binary.getRight());
      } else {
        errors.add("the property path " + path + " is not supported");
      }
    }

    private void predicateFunction(final Node predicate) {
      if (predicate.isURI() && propertyFunctions.manages(predicate.getURI())) {
        errors.add(
            "the predicate <"
                + predicate.getURI()
                + "> names a property function, which runs code rather than matching data");
      }
    }

    /**
     * A FILTER expression: positive EXISTS is allowed only as a condition it requires. The body's
     * own conditions, {@code branches} not null, are kept for witnesses.
     */
    private void condition(final Expr expr, final int depth, final List<Branch> branches) {
      if (!checkDepth(depth)) {
        return;
      }
      if (expr instanceof E_LogicalAnd and) {
        condition(and.getArg1(), depth + 1, branches);
        condition(and.getArg2(), depth + 1, branches);
      } else if (expr instanceof E_Exists exists) {
        existsPattern(exists.getGraphPattern(), depth + 1);
        if (branches != null) {
          conditions.add(new Condition(exists.getGraphPattern(), branches));
        }
      } else {
        expression(expr, depth);
      }
    }

    private void expression(final Expr expr, final int depth) {
      if (!checkDepth(depth)) {
        return;
      }
      if (expr instanceof E_NotExists) {
        errors.add("NOT EXISTS is not allowed; rules must only add facts as their inputs grow");
        return;
      }
      if (expr instanceof E_Exists) {
        errors.add(
            "EXISTS is only allowed as a condition the whole FILTER requires; negated or computed"
                + " with, as in !EXISTS, it can turn false as facts arrive");
        return;
      }
      if (expr instanceof ExprAggregator) {
        errors.add("aggregates are not allowed");
        return;
      }
      if (expr instanceof ExprFunctionOp) {
        errors.add("graph patterns inside expressions are not allowed");
        return;
      }
      if (expr instanceof E_Bound) {
        errors.add("BOUND is not allowed; testing for missing values makes a rule non-monotone");
      } else if (expr instanceof Unstable || expr instanceof E_Now) {
        errors.add(
            "the function %s is nondeterministic; every pass must see the same values"
                .formatted(((ExprFunction) expr).getFunctionSymbol().getSymbol()));
      } else if (expr instanceof E_Call) {
        errors.add("CALL is not allowed; it chooses the function to run from the data");
      } else if (expr instanceof E_Function function) {
        final String iri = function.getFunctionIRI();
        if (FN_NONDETERMINISTIC.contains(iri)) {
          errors.add(
              "the function <%s> is nondeterministic; every pass must see the same values"
                  .formatted(iri));
        } else if (!iri.startsWith(XSD) && !iri.startsWith(FN)) {
          errors.add(
              "the function <%s> is an extension function; only SPARQL functions and XSD casts"
                      .formatted(iri)
                  + " may be called");
        }
      }
      if (expr instanceof ExprFunction function) {
        for (final Expr arg : function.getArgs()) {
          expression(arg, depth + 1);
        }
      }
    }

    /**
     * An EXISTS pattern is checked like the body, but its atoms are not variants' atoms and bind
     * nothing the template may use: its solutions never leave the filter.
     */
    private void existsPattern(final Op op, final int depth) {
      if (!checkDepth(depth)) {
        return;
      }
      if (op instanceof OpBGP bgp) {
        bgp.getPattern().getList().forEach(this::existsTriple);
      } else if (op instanceof OpTriple triple) {
        existsTriple(triple.getTriple());
      } else if (op instanceof OpPath path) {
        patterns++;
        pathPredicates(path.getTriplePath().getPath());
      } else if (op instanceof OpJoin join) {
        existsPattern(join.getLeft(), depth + 1);
        existsPattern(join.getRight(), depth + 1);
      } else if (op instanceof OpSequence sequence) {
        sequence.getElements().forEach(element -> existsPattern(element, depth + 1));
      } else if (op instanceof OpUnion union) {
        existsPattern(union.getLeft(), depth + 1);
        existsPattern(union.getRight(), depth + 1);
      } else if (op instanceof OpFilter filter) {
        filter.getExprs().forEach(expr -> condition(expr, depth + 1, null));
        existsPattern(filter.getSubOp(), depth + 1);
      } else if (op instanceof OpExtend || op instanceof OpAssign) {
        final OpExtendAssign extend = (OpExtendAssign) op;
        extend.getVarExprList().forEachExpr((var, expr) -> expression(expr, depth + 1));
        existsPattern(extend.getSubOp(), depth + 1);
      } else if (!(op instanceof OpTable) && !(op instanceof OpNull)) {
        refuse(op);
      }
    }

    private void existsTriple(final Triple triple) {
      patterns++;
      predicateFunction(triple.getPredicate());
      opaque.add(triple.getPredicate().isVariable() ? CompiledRule.ANY : triple.getPredicate());
    }

    private void refuse(final Op op) {
      final String construct;
      if (op instanceof OpLeftJoin || op instanceof OpConditional) {
        construct = "OPTIONAL is not allowed; rules must only add facts as their inputs grow";
      } else if (op instanceof OpMinus) {
        construct = "MINUS is not allowed; rules must only add facts as their inputs grow";
      } else if (op instanceof OpService) {
        construct = "SERVICE is not allowed; rules read only the working dataset";
      } else if (op instanceof OpGraph
          || op instanceof OpQuadPattern
          || op instanceof OpQuad
          || op instanceof OpQuadBlock
          || op instanceof OpDatasetNames) {
        construct = "GRAPH is not allowed; rules read the union of the graphs the step selects";
      } else if (op instanceof OpModifier || op instanceof OpTopN) {
        construct =
            "nested subqueries are not allowed; they can aggregate or limit results, which a fixed"
                + " point cannot repeat safely";
      } else if (op instanceof OpLateral) {
        construct = "LATERAL is not allowed";
      } else if (op instanceof OpPropFunc || op instanceof OpProcedure) {
        construct = "property functions and procedures are not allowed; they run code";
      } else {
        construct = "the operator " + op.getName() + " is not allowed in a rule";
      }
      errors.add(construct);
    }

    private boolean checkDepth(final int depth) {
      if (depth > MAX_DEPTH) {
        if (!tooDeep) {
          tooDeep = true;
          problems.add(problem(name, "RULE_TOO_LARGE", "the body nests deeper than " + MAX_DEPTH));
        }
        return false;
      }
      return true;
    }

    void finish() {
      for (final String error : errors) {
        problems.add(problem(name, code(error), error));
      }
      errors.clear();
    }

    private static String code(final String error) {
      if (error.contains("nondeterministic")) {
        return "RULE_NONDETERMINISTIC";
      }
      if (error.startsWith("OPTIONAL")
          || error.startsWith("MINUS")
          || error.startsWith("NOT EXISTS")
          || error.startsWith("EXISTS")
          || error.startsWith("BOUND")) {
        return "RULE_NOT_MONOTONIC";
      }
      return "RULE_UNSUPPORTED";
    }
  }
}
