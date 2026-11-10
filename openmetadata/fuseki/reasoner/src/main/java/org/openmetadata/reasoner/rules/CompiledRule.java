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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpAssign;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpDisjunction;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpQuadPattern;
import org.apache.jena.sparql.algebra.op.OpSequence;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;

/**
 * A rule the compiler accepted: its WHERE pattern in algebra, with every UNION branch marked so a
 * solution says which branch it came from, its body atoms numbered in pattern order, and its
 * template. Instances are immutable and shared by every pass.
 *
 * <p>Semi-naive evaluation rewrites the pattern once per atom: in the variant for atom {@code k},
 * that triple pattern matches only the previous pass's new facts (a delta graph), every other
 * pattern matches everything, and UNION branches that do not contain {@code k} are dropped, since
 * their solutions use no new fact. A solution that uses at least one new fact is found by the
 * variant of one atom it matched there; a solution that uses none was found by an earlier pass.
 * Property paths and EXISTS conditions cannot be split that way; a rule whose path or EXISTS may
 * read a predicate the delta holds is evaluated in full in that pass instead, and TDB2 drops the
 * facts it already has.
 *
 * @param index position in the bundle, which the spool records instead of the name
 * @param pattern the WHERE pattern, union branches marked
 * @param atoms the triple and path patterns outside EXISTS, in the order the variants number them
 * @param conditions the EXISTS conditions outside EXISTS, which a witness reconstructs
 * @param ranges for each operator of {@code pattern}, the atoms {@code [from, to)} under it
 * @param opaquePredicates the predicates paths and EXISTS conditions can read; {@link #ANY} when
 *     one of them can read any
 * @param witnessVars the variables a witness records, in a fixed order
 */
record CompiledRule(
    int index,
    String name,
    String body,
    String digest,
    Node output,
    Op pattern,
    List<Triple> template,
    List<Atom> atoms,
    List<Condition> conditions,
    Map<Op, int[]> ranges,
    Set<Node> opaquePredicates,
    List<Var> witnessVars) {

  /** Stands for every predicate: a variable predicate, or a negated property set. */
  static final Node ANY = Node.ANY;

  /**
   * A triple or path pattern of the body, outside EXISTS, and the union branches it sits in: a
   * solution matched it only if each marker variable is bound to the given side.
   */
  record Atom(int index, Triple triple, TriplePath path, List<Branch> branches) {
    boolean isPath() {
      return path != null;
    }

    /** The predicate a delta fact must have to match this atom; {@link #ANY} for a variable. */
    Node predicate() {
      final Node predicate = triple.getPredicate();
      return predicate.isVariable() ? ANY : predicate;
    }

    boolean matchedBy(final Binding solution) {
      return Branch.allMatch(branches, solution);
    }
  }

  /** A FILTER EXISTS pattern of the body and the union branches it sits in. */
  record Condition(Op pattern, List<Branch> branches) {
    boolean matchedBy(final Binding solution) {
      return Branch.allMatch(branches, solution);
    }
  }

  record Branch(Var marker, Node side) {
    static boolean allMatch(final List<Branch> branches, final Binding solution) {
      for (final Branch branch : branches) {
        final Node side = solution.get(branch.marker());
        if (side == null || !side.equals(branch.side())) {
          return false;
        }
      }
      return true;
    }
  }

  boolean hasOpaqueParts() {
    return !opaquePredicates.isEmpty();
  }

  /**
   * Whether a delta with these predicates can give the rule's paths or EXISTS conditions a new
   * answer. {@code deltaPredicates} holds {@link #ANY} when the delta has too many to list.
   */
  boolean opaquePartsRead(final Set<Node> deltaPredicates) {
    if (opaquePredicates.isEmpty() || deltaPredicates.isEmpty()) {
      return false;
    }
    if (opaquePredicates.contains(ANY) || deltaPredicates.contains(ANY)) {
      return true;
    }
    for (final Node predicate : opaquePredicates) {
      if (deltaPredicates.contains(predicate)) {
        return true;
      }
    }
    return false;
  }

  /** The atoms whose variant can match a delta with these predicates. */
  List<Atom> deltaAtoms(final Set<Node> deltaPredicates) {
    final List<Atom> matching = new ArrayList<>();
    if (deltaPredicates.isEmpty()) {
      return matching;
    }
    for (final Atom atom : atoms) {
      if (!atom.isPath()
          && (atom.predicate() == ANY
              || deltaPredicates.contains(ANY)
              || deltaPredicates.contains(atom.predicate()))) {
        matching.add(atom);
      }
    }
    return matching;
  }

  /** The pattern with atom {@code k} matching only the facts in {@code delta}. */
  Op variant(final int k, final Node delta) {
    return rewrite(pattern, k, delta);
  }

  private Op rewrite(final Op op, final int k, final Node delta) {
    if (op instanceof OpBGP bgp) {
      return deltaFirst(bgp.getPattern().getList(), k, delta, op);
    }
    if (op instanceof OpTriple triple) {
      return deltaFirst(List.of(triple.getTriple()), k, delta, op);
    }
    if (op instanceof OpUnion union) {
      if (contains(union.getLeft(), k)) {
        return rewrite(union.getLeft(), k, delta);
      }
      return contains(union.getRight(), k) ? rewrite(union.getRight(), k, delta) : op;
    }
    if (!contains(op, k)) {
      return op;
    }
    if (op instanceof OpJoin join) {
      return OpJoin.create(rewrite(join.getLeft(), k, delta), rewrite(join.getRight(), k, delta));
    }
    if (op instanceof OpSequence sequence) {
      final OpSequence copy = OpSequence.create();
      sequence.getElements().forEach(element -> copy.add(rewrite(element, k, delta)));
      return copy;
    }
    if (op instanceof OpDisjunction disjunction) {
      for (final Op element : disjunction.getElements()) {
        if (contains(element, k)) {
          return rewrite(element, k, delta);
        }
      }
      return op;
    }
    if (op instanceof OpFilter filter) {
      return OpFilter.filterBy(filter.getExprs(), rewrite(filter.getSubOp(), k, delta));
    }
    if (op instanceof OpExtend extend) {
      return OpExtend.create(rewrite(extend.getSubOp(), k, delta), extend.getVarExprList());
    }
    if (op instanceof OpAssign assign) {
      return OpAssign.create(rewrite(assign.getSubOp(), k, delta), assign.getVarExprList());
    }
    throw new IllegalStateException("The compiler admitted an operator it cannot rewrite: " + op);
  }

  /** The delta pattern first, so it drives the join; the rest of the block keeps its order. */
  private Op deltaFirst(final List<Triple> triples, final int k, final Node delta, final Op op) {
    final int from = ranges.get(op)[0];
    if (k < from || k >= from + triples.size()) {
      return op;
    }
    final BasicPattern deltaPattern = new BasicPattern();
    deltaPattern.add(triples.get(k - from));
    final Op deltaOp = new OpQuadPattern(delta, deltaPattern);
    if (triples.size() == 1) {
      return deltaOp;
    }
    final BasicPattern rest = new BasicPattern();
    for (int i = 0; i < triples.size(); i++) {
      if (i != k - from) {
        rest.add(triples.get(i));
      }
    }
    return OpSequence.create(deltaOp, new OpBGP(rest));
  }

  private boolean contains(final Op op, final int k) {
    final int[] range = ranges.get(op);
    return range != null && k >= range[0] && k < range[1];
  }

  static Map<Op, int[]> identityMap() {
    return new IdentityHashMap<>();
  }
}
