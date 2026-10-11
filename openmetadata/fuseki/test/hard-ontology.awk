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
#
# Writes, as TriG, a small closure that HermiT cannot classify in minutes: n classes, each defined
# as an intersection with an existential over a union, plus a universal restriction. With n=200 it
# is 58 KB and was still unfinished after 240 s with a 2 GiB heap, using over 1 GiB of it.
# Deterministic: Park-Miller pseudo-random numbers, exact in awk's doubles, the same sequence as
# Jobs.hardOntology in the reasoner tests. Usage: awk -v n=200 -f hard-ontology.awk
function next_index() {
  seed = (seed * 16807) % 2147483647
  return seed % n
}
BEGIN {
  seed = 7
  print "PREFIX owl: <http://www.w3.org/2002/07/owl#>"
  print "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
  print "PREFIX : <http://example.org/hard#>"
  print "GRAPH <urn:hard> {"
  print "<http://example.org/hard> a owl:Ontology ."
  print ":r a owl:ObjectProperty . :s a owl:ObjectProperty ."
  for (i = 0; i < n; i++) print ":C" i " a owl:Class ."
  for (i = 0; i < n; i++) {
    a = next_index(); b = next_index(); c = next_index(); d = next_index()
    if (b == c) c = (c + 1) % n
    print ":C" i " owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :C" a " [ a owl:Restriction ; owl:onProperty :r ; owl:someValuesFrom [ a owl:Class ; owl:unionOf ( :C" b " :C" c " ) ] ] ) ] ."
    print ":C" i " rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :s ; owl:allValuesFrom :C" d " ] ."
  }
  print "}"
}
