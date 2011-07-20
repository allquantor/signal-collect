/*
 *  @author Philip Stutz
 *  
 *  Copyright 2010 University of Zurich
 *      
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 */

package com.signalcollect.examples

import com.signalcollect.api._
import com.signalcollect.configuration._

/**
 * Represents an edge in a Single-Source Shortest Path compute graph.
 *  The edge weight corresponds to the length of the path represented by
 *  this edge.
 *
 *  @param s: the identifier of the source vertex
 *  @param t: the identifier of the target vertex
 */
class Path(s: Any, t: Any) extends OptionalSignalEdge(s, t) {

  /**
   * Specifies the type of the source vertex.
   *  This avoids type-checks/-casts, for example when accessing source.state.
   */
  type SourceVertexType = Location
 
  /**
   * The signal function calculates the distance of the shortest currently
   *  known path from the SSSP source vertex which passes through the source
   *  vertex of this edge. This is obviously the shortest distance to the vertex
   *  where this edge starts plus the length of the path represented by this
   *  edge (= the weight of this edge).
   */
  def signal(sourceVertex: Location): Option[Int] = {
    sourceVertex.state map (_ + weight.toInt)
  }
}

/**
 * Represents a location in a SSSP compute graph
 *
 *  @param id: the identifier of this vertex
 *  @param initialDIstance: the initial distance of this vertex to the source location
 *  if the distance is Int.MaxValue this means that there is no known path. If the distance is
 *  0 this means that this vertex is the source location.
 */
class Location(id: Any, initialDistance: Option[Int] = None) extends SignalMapVertex(id, initialDistance) {

  /**
   * The collect function calculates shortest currently known path
   *  from the source location. This is obviously either the shortest known path
   *  up to now (= state) or one of the paths that had been advertised via a signal
   *  by a neighbor.
   */
  def collect: Option[Int] = {
    if(signals(classOf[Int]).isEmpty && state == None) { //nothing changed
    None
    }
    else {
      Some(signals(classOf[Int]).foldLeft(state.getOrElse(Int.MaxValue))(math.min(_, _)))
    }
  }

}

/** Builds a Single-Source Shortest Path compute graph and executes the computation */
object SSSP extends App {
  val cg = new ComputeGraphBuilder().build
  cg.addVertex(new Location(1, Some(0)))
  cg.addVertex(new Location(2))
  cg.addVertex(new Location(3))
  cg.addVertex(new Location(4))
  cg.addVertex(new Location(5))
  cg.addVertex(new Location(6))
  cg.addEdge(new Path(1, 2))
  cg.addEdge(new Path(2, 3))
  cg.addEdge(new Path(3, 4))
  cg.addEdge(new Path(1, 5))
  cg.addEdge(new Path(4, 6))
  cg.addEdge(new Path(5, 6))
  val stats = cg.execute
  println(stats)
  cg.foreachVertex(println(_))
  cg.shutdown
}