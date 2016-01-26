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

package com.signalcollect.interfaces

import java.util.concurrent.atomic.AtomicInteger
import com.signalcollect.GraphEditor
import akka.actor.ActorRef
import akka.event.Logging.LogEvent

/**
 *  A message bus is responsible for sending messages.
 *  It has to guarantee per-sender FIFO when delivering messages.
 */
trait MessageBus[@specialized(Int, Long) Id, Signal] extends MessageRecipientRegistry with VertexToWorkerMapper[Id] {
  def flush(): Unit

  def isInitialized: Boolean

  def numberOfWorkers: Int

  def numberOfNodes: Int

  def incrementMessagesSentToWorker(workerId: Int): Unit
  def messagesSentToWorkers(): Array[Int]

  def incrementMessagesSentToNode(nodeId: Int): Unit
  def messagesSentToNodes(): Array[Int]

  def incrementMessagesSentToCoordinator(): Unit
  def messagesSentToCoordinator(): Int

  def incrementMessagesSentToOthers(): Unit
  def messagesSentToOthers(): Int

  def messagesReceived: Long

  def getReceivedMessagesCounter(): AtomicInteger

  def sendToActor(actor: ActorRef, message: Any): Unit

  def sendToWorkerForVertexIdHash(message: Any, vertexIdHash: Int): Unit

  def sendToWorkerForVertexId(message: Any, vertexId: Id): Unit

  def sendToWorker(workerId: Int, message: Any): Unit

  def sendToWorkerUncounted(workerId: Int, message: Any): Unit

  def sendToWorkers(message: Any, messageCounting: Boolean): Unit

  def sendToNode(nodeId: Int, message: Any): Unit

  def sendToNodeUncounted(nodeId: Int, message: Any): Unit

  def sendToNodes(message: Any, messageCounting: Boolean): Unit

  def sendToCoordinator(message: Any): Unit

  def sendToCoordinatorUncounted(message: Any): Unit

  /**
   * Resets the message but does not touch the counters.
   */
  def reset(): Unit

  // Returns an api that treats all workers as if there were only one.
  def getWorkerApi(): WorkerApi[Id, Signal]

  // Returns an array of worker proxies for all workers, indexed by workerId.
  def getWorkerProxies(): Array[WorkerApi[Id, Signal]]

  // Returns a graph editor that allows to manipulate the graph.
  def getGraphEditor(): GraphEditor[Id, Signal]
}

trait MessageRecipientRegistry {

  /**
   *  Registers a worker.
   *
   *  @param workerId is the worker id
   *  @param worker is the worker to be registered
   */
  def registerWorker(workerId: Int, worker: ActorRef): Unit

  /**
   *  Registers a node.
   *
   *  @param nodeId is the node id
   *  @param node is the node to be registered
   */
  def registerNode(nodeId: Int, node: ActorRef): Unit

  /**
   *  Registers a coordinator.
   *
   *  @param coordinator is the coordinator to be registered
   */
  def registerCoordinator(coordinator: ActorRef): Unit

}
