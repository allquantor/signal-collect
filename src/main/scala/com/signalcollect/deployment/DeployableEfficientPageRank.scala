/*
 *  @author Philip Stutz
 *  @author Tobias Bachmann
 *  
 *  Copyright 2014 University of Zurich
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

package com.signalcollect.deployment

import com.signalcollect._
import com.signalcollect.configuration.ExecutionMode
import com.signalcollect.examples.EfficientPageRankVertex
import com.signalcollect.examples.PlaceholderEdge
import java.io.FileInputStream
import scala.collection.mutable.ArrayBuffer
import com.signalcollect.util.Ints
import java.io.DataInputStream

/** Builds a PageRank compute graph and executes the computation */
object DeployableEfficientPageRank extends Algorithm{
  
  override def configureGraphBuilder(graphBuilder: GraphBuilder[Any,Any]): GraphBuilder[Any,Any] = {
    graphBuilder.withEagerIdleDetection(false)
    //    .withConsole(true)
  }
  
  override def reportResults(stats: ExecutionInformation, graph: Graph[Any,Any]){
    println(stats)

    graph.foreachVertex(println(_))
  }
  
  override def loadGraph(graph: Graph[Any,Any]): Graph[Any, Any] = {
    graph.addVertex(new EfficientPageRankVertex(1))
    graph.addVertex(new EfficientPageRankVertex(2))
    graph.addVertex(new EfficientPageRankVertex(3))
    graph.addEdge(1, new PlaceholderEdge(2))
    graph.addEdge(2, new PlaceholderEdge(1))
    graph.addEdge(2, new PlaceholderEdge(3))
    graph.addEdge(3, new PlaceholderEdge(2))
    graph
  }
  
  def execute(parameters: Map[String, String], graphBuilder: GraphBuilder[Any, Any]) {
    val graph = graphBuilder.
      withEagerIdleDetection(false).
      //    withConsole(true).
      build
    graph.awaitIdle
    println("build graph")
    graph.addVertex(new EfficientPageRankVertex(1))
    graph.addVertex(new EfficientPageRankVertex(2))
    graph.addVertex(new EfficientPageRankVertex(3))
    graph.addEdge(1, new PlaceholderEdge(2))
    graph.addEdge(2, new PlaceholderEdge(1))
    graph.addEdge(2, new PlaceholderEdge(3))
    graph.addEdge(3, new PlaceholderEdge(2))
    println("await idle")
    graph.awaitIdle
    println("execute")
    val stats = graph.execute //(ExecutionConfiguration.withExecutionMode(ExecutionMode.Interactive))
    println(stats)

    graph.foreachVertex(println(_))
    graph.shutdown
    
   
  }
  
  
}

//case class SplitLoader(splitFileName: String) extends Iterator[GraphEditor[Int, Double] => Unit] {
//
//  lazy val in = new DataInputStream(new FileInputStream(splitFileName))
//
//  var loaded = 0
//
//  def readNext: Int = Ints.readUnsignedVarInt(in)
//
//  def nextVertices(length: Int): ArrayBuffer[Int] = {
//    val vertices = new ArrayBuffer[Int]
//    while (vertices.length < length) {
//      val nextVertex = readNext
//      vertices += nextVertex
//    }
//    vertices
//  }
//
//  def addVertex(vertex: Vertex[Int, Double])(graphEditor: GraphEditor[Int, Double]) {
//    graphEditor.addVertex(vertex)
//  }
//
//  var vertexId = Int.MinValue
//
//  def hasNext = {
//    if (vertexId == Int.MinValue) {
//      vertexId = readNext
//    }
//    vertexId >= 0
//  }
//
//  def next: GraphEditor[Int, Double] => Unit = {
//    loaded += 1
//    if (loaded % 10000 == 0) {
//      println(loaded)
//    }
//    val verticesIds = nextVertices(1000)
//    val vertices = verticesIds.foreach( v => addVertex(new EfficientPageRankVertex(v)) _)
////    vertex.setTargetIds(edges.length, Ints.createCompactSet(edges.toArray))
//  }
//}
