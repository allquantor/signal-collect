package com.signalcollect

import org.scalatest.{ Finders, FlatSpec, Matchers }

import com.signalcollect.examples.{ PageRankEdge, PageRankVertex }
import com.signalcollect.interfaces.{ EdgeAddedToNonExistentVertexHandler, EdgeAddedToNonExistentVertexHandlerFactory }
import com.signalcollect.util.TestAnnouncements

class TestEdgeAddedToNonExistentVertexHandlerFactory extends EdgeAddedToNonExistentVertexHandlerFactory[Any, Any] {
  def createInstance: EdgeAddedToNonExistentVertexHandler[Any, Any] =
    new TestEdgeAddedToNonExistentVertexHandler
  override def toString = "TestEdgeAddedToNonExistentVertexHandlerFactory"
}

class TestEdgeAddedToNonExistentVertexHandler extends EdgeAddedToNonExistentVertexHandler[Any, Any] {
  def handleImpossibleEdgeAddition(edge: Edge[Any], vertexId: Any, graphEditor: GraphEditor[Any, Any]): Option[Vertex[Any, _, Any, Any]] = {
    val v = new PageRankVertex[Any](vertexId)
    Some(v)
  }
}

class NonExistentVertexHandlerSpec extends FlatSpec with Matchers with TestAnnouncements {

  "Handler for adding an edge to a nonexistent vertex" should "correctly create vertices if needed" in {
    val system = TestConfig.actorSystem(port = 2556)
    val g = GraphBuilder
      .withActorSystem(system)
      .withEdgeAddedToNonExistentVertexHandlerFactory(new TestEdgeAddedToNonExistentVertexHandlerFactory)
      .build
    try {
      g.addEdge(1, new PageRankEdge(2))
      g.addEdge(2, new PageRankEdge(1))
      val stats = g.execute
      assert(stats.aggregatedWorkerStatistics.numberOfVertices == 2)
    } finally {
      g.shutdown
      g.system.shutdown()
    }
  }

}
