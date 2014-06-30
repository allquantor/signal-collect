/*
 *  @author Philip Stutz
 *  @author Mihaela Verman
 *
 *  Copyright 2012 University of Zurich
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

package com.signalcollect.worker

import com.signalcollect.interfaces.Storage
import com.signalcollect.interfaces.ComplexAggregation
import com.signalcollect.GraphEditor
import com.signalcollect.interfaces.EdgeId
import com.signalcollect.Vertex
import com.signalcollect.Edge
import com.signalcollect.interfaces.MessageBus
import com.signalcollect.interfaces.WorkerApi
import akka.event.LoggingAdapter
import com.signalcollect.interfaces.WorkerStatistics
import com.signalcollect.interfaces.NodeStatistics
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.signalcollect.interfaces.StorageFactory
import com.signalcollect.interfaces.WorkerStatus
import akka.actor.ActorRef
import com.signalcollect.interfaces.MessageRecipientRegistry
import com.signalcollect.interfaces.Worker
import com.signalcollect.interfaces.SentMessagesStats
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import com.signalcollect.interfaces.WorkerStatistics
import com.signalcollect.interfaces.NodeStatistics
import com.signalcollect.interfaces.SchedulerFactory
import akka.serialization.Serialization

/**
 * Main implementation of the WorkerApi interface.
 */
class WorkerImplementation[Id, Signal](
  val workerId: Int,
  val nodeId: Int,
  val eagerIdleDetection: Boolean,
  val serialization: Serialization, // Used for snapshot/restore
  val messageBus: MessageBus[Id, Signal],
  val log: LoggingAdapter,
  val storageFactory: StorageFactory,
  val schedulerFactory: SchedulerFactory,
  var signalThreshold: Double,
  var collectThreshold: Double,
  var existingVertexHandler: (Vertex[_, _], Vertex[_, _], GraphEditor[Id, Signal]) => Unit,
  var undeliverableSignalHandler: (Signal, Id, Option[Id], GraphEditor[Id, Signal]) => Unit,
  var edgeAddedToNonExistentVertexHandler: (Edge[Id], Id) => Option[Vertex[Id, _]])
  extends Worker[Id, Signal] {

  val scheduler = schedulerFactory.createInstance(this)
  val graphEditor: GraphEditor[Id, Signal] = new WorkerGraphEditor(workerId, this, messageBus)
  val vertexGraphEditor: GraphEditor[Any, Any] = graphEditor.asInstanceOf[GraphEditor[Any, Any]]

  initialize

  var messageBusFlushed: Boolean = _
  var isIdleDetectionEnabled: Boolean = _
  var systemOverloaded: Boolean = _ // If the coordinator allows this worker to signal.
  var operationsScheduled: Boolean = _ // If executing operations has been scheduled.
  var isIdle: Boolean = _ // Idle status that was last reported to the coordinator.
  var isPaused: Boolean = _
  var allWorkDoneWhenContinueSent: Boolean = _
  var lastStatusUpdate: Long = _
  var vertexStore: Storage[Id] = _
  var pendingModifications: Iterator[GraphEditor[Id, Signal] => Unit] = _
  val counters: WorkerOperationCounters = new WorkerOperationCounters()

  def initialize {
    messageBusFlushed = true
    isIdleDetectionEnabled = false
    systemOverloaded = false
    operationsScheduled = false
    isIdle = true
    isPaused = true
    allWorkDoneWhenContinueSent = false
    lastStatusUpdate = System.currentTimeMillis
    vertexStore = storageFactory.createInstance[Id]
    pendingModifications = Iterator.empty
  }

  /**
   * Resets all state apart from that which is part of the constructor.
   * Also does not reset the part of the counters which is part of
   * termination detection.
   */
  def reset {
    initialize
    counters.resetOperationCounters
    messageBus.reset
  }

  def isAllWorkDone: Boolean = {
    if (isPaused) {
      pendingModifications.isEmpty
    } else {
      isConverged
    }
  }

  def initializeIdleDetection {
    isIdleDetectionEnabled = true
  }

  def setIdle(newIdleState: Boolean) {
    isIdle = newIdleState
    if (eagerIdleDetection && isIdleDetectionEnabled) {
      messageBus.sendToNode(nodeId, getWorkerStatusForNode)
    }
  }

  def sendStatusToCoordinator {
    if (messageBus.isInitialized) {
      val status = getWorkerStatusForCoordinator
      messageBus.sendToCoordinator(status)
    }
  }

  def isConverged: Boolean = {
    vertexStore.toCollect.isEmpty &&
      vertexStore.toSignal.isEmpty &&
      messageBusFlushed
  }

  def executeCollectOperationOfVertex(vertex: Vertex[Id, _], addToSignal: Boolean = true) {
    counters.collectOperationsExecuted += 1
    vertex.executeCollectOperation(vertexGraphEditor)
    if (addToSignal && vertex.scoreSignal > signalThreshold) {
      vertexStore.toSignal.put(vertex)
    }
  }

  def executeSignalOperationOfVertex(vertex: Vertex[Id, _]) {
    counters.signalOperationsExecuted += 1
    vertex.executeSignalOperation(vertexGraphEditor)
  }

  def processSignal(signal: Signal, targetId: Id, sourceId: Option[Id]) {
    val vertex = vertexStore.vertices.get(targetId)
    if (vertex != null) {
      if (vertex.deliverSignal(signal, sourceId, vertexGraphEditor)) {
        counters.collectOperationsExecuted += 1
        if (vertex.scoreSignal > signalThreshold) {
          scheduler.handleCollectOnDelivery(vertex)
        }
      } else {
        if (vertex.scoreCollect > collectThreshold) {
          vertexStore.toCollect.put(vertex)
        }
      }
    } else {
      undeliverableSignalHandler(signal, targetId, sourceId, graphEditor)
    }
    messageBusFlushed = false
  }

  def startComputation {
    if (!pendingModifications.isEmpty) {
      log.warning("Need to call `awaitIdle` after executiong `loadGraph` or pending operations are ignored.")
    }
    isPaused = false
    sendStatusToCoordinator
  }

  def pauseComputation {
    isPaused = true
    sendStatusToCoordinator
  }

  def signalStep: Boolean = {
    counters.signalSteps += 1
    vertexStore.toSignal.process(executeSignalOperationOfVertex(_))
    messageBus.flush
    messageBusFlushed = true
    vertexStore.toCollect.isEmpty
  }

  def collectStep: Boolean = {
    counters.collectSteps += 1
    vertexStore.toCollect.process(executeCollectOperationOfVertex(_))
    vertexStore.toSignal.isEmpty
  }

  override def addVertex(vertex: Vertex[Id, _]) {
    if (vertexStore.vertices.put(vertex)) {
      counters.verticesAdded += 1
      counters.outgoingEdgesAdded += vertex.edgeCount
      vertex.afterInitialization(vertexGraphEditor)
      messageBusFlushed = false
      if (vertex.scoreSignal > signalThreshold) {
        vertexStore.toSignal.put(vertex)
      }
    } else {
      val existing = vertexStore.vertices.get(vertex.id)
      existingVertexHandler(existing, vertex, graphEditor)
    }
  }

  override def addEdge(sourceId: Id, edge: Edge[Id]) {
    def addEdgeToVertex(vertex: Vertex[Id, _]) {
      if (vertex.addEdge(edge, vertexGraphEditor)) {
        counters.outgoingEdgesAdded += 1
        if (vertex.scoreSignal > signalThreshold) {
          vertexStore.toSignal.put(vertex)
        }
      }
    }
    val v = vertexStore.vertices.get(sourceId)
    if (v == null) {
      val vertexOption = edgeAddedToNonExistentVertexHandler(edge, sourceId)
      if (vertexOption.isDefined) {
        addVertex(vertexOption.get)
        addEdgeToVertex(vertexOption.get)
      }
    } else {
      addEdgeToVertex(v)
    }
  }

  override def removeEdge(edgeId: EdgeId[Id]) {
    val vertex = vertexStore.vertices.get(edgeId.sourceId)
    if (vertex != null) {
      if (vertex.removeEdge(edgeId.targetId, vertexGraphEditor)) {
        counters.outgoingEdgesRemoved += 1
        if (vertex.scoreSignal > signalThreshold) {
          vertexStore.toSignal.put(vertex)
        }
      } else {
        log.warning("Outgoing edge not found when trying to remove edge with id " + edgeId)
      }
    } else {
      log.warning("Source vertex not found found when trying to remove outgoing edge with id " + edgeId)
    }
  }

  override def removeVertex(vertexId: Id) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      processRemoveVertex(vertex)
    } else {
      log.warning("Should remove vertex with id " + vertexId + ": could not find this vertex.")
    }
  }

  protected def processRemoveVertex(vertex: Vertex[Id, _]) {
    val edgesRemoved = vertex.removeAllEdges(vertexGraphEditor)
    counters.outgoingEdgesRemoved += edgesRemoved
    counters.verticesRemoved += 1
    vertex.beforeRemoval(vertexGraphEditor)
    vertexStore.vertices.remove(vertex.id)
    vertexStore.toCollect.remove(vertex.id)
    vertexStore.toSignal.remove(vertex.id)
  }

  def modifyGraph(graphModification: GraphEditor[Id, Signal] => Unit, vertexIdHint: Option[Id]) {
    graphModification(graphEditor)
    messageBusFlushed = false
  }

  def loadGraph(graphModifications: Iterator[GraphEditor[Id, Signal] => Unit], vertexIdHint: Option[Id]) {
    pendingModifications = pendingModifications ++ graphModifications
  }

  def setExistingVertexHandler(h: (Vertex[_, _], Vertex[_, _], GraphEditor[Id, Signal]) => Unit) {
    existingVertexHandler = h
  }

  def setUndeliverableSignalHandler(h: (Signal, Id, Option[Id], GraphEditor[Id, Signal]) => Unit) {
    undeliverableSignalHandler = h
  }

  def setEdgeAddedToNonExistentVertexHandler(h: (Edge[Id], Id) => Option[Vertex[Id, _]]) {
    edgeAddedToNonExistentVertexHandler = h
  }

  def setSignalThreshold(st: Double) {
    signalThreshold = st
  }

  def setCollectThreshold(ct: Double) {
    collectThreshold = ct
  }

  def recalculateScores {
    vertexStore.vertices.foreach(recalculateVertexScores(_))
  }

  def recalculateScoresForVertexWithId(vertexId: Id) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      recalculateVertexScores(vertex)
    }
  }

  protected def recalculateVertexScores(vertex: Vertex[Id, _]) {
    if (vertex.scoreCollect > collectThreshold) {
      vertexStore.toCollect.put(vertex)
    }
    if (vertex.scoreSignal > signalThreshold) {
      vertexStore.toSignal.put(vertex)
    }
  }

  override def forVertexWithId[VertexType <: Vertex[Id, _], ResultType](vertexId: Id, f: VertexType => ResultType): ResultType = {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      val result = f(vertex.asInstanceOf[VertexType])
      result
    } else {
      throw new Exception("Vertex with id " + vertexId + " not found.")
    }
  }

  override def foreachVertex(f: Vertex[Id, _] => Unit) {
    vertexStore.vertices.foreach(f)
  }

  override def foreachVertexWithGraphEditor(f: GraphEditor[Id, Signal] => Vertex[Id, _] => Unit) {
    val function = f(graphEditor)
    vertexStore.vertices.foreach(function)
    messageBusFlushed = false
  }

  override def aggregateOnWorker[WorkerResult](aggregationOperation: ComplexAggregation[WorkerResult, _]): WorkerResult = {
    aggregationOperation.aggregationOnWorker(vertexStore.vertices.stream)
  }

  override def aggregateAll[WorkerResult, EndResult](aggregationOperation: ComplexAggregation[WorkerResult, EndResult]): EndResult = {
    throw new UnsupportedOperationException("AkkaWorker does not support this operation.")
  }

  /**
   * Creates a snapshot of all the vertices in all workers.
   * Does not store the toSignal/toCollect collections or pending messages.
   * Should only be used when the workers are idle.
   * Overwrites any previous snapshot that might exist.
   */
  override def snapshot {
    // Overwrites previous file if it should exist.
    val snapshotFileOutput = new DataOutputStream(new FileOutputStream(s"$workerId.snapshot"))
    try {
      val serializer = serialization.serializerFor(classOf[Vertex[_, _]])
      vertexStore.vertices.foreach { vertex =>
        val bytes = serializer.toBinary(vertex)
        snapshotFileOutput.writeInt(bytes.length)
        snapshotFileOutput.write(bytes)
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace()
    } finally {
      snapshotFileOutput.close
    }
  }

  /**
   * Restores the last snapshot of all the vertices in all workers.
   * Does not store the toSignal/toCollect collections or pending messages.
   * Should only be used when the workers are idle.
   */
  override def restore {
    reset
    val maxSerializedSize = 64768
    val snapshotFile = new File(s"$workerId.snapshot")
    val buffer = new Array[Byte](maxSerializedSize)
    if (snapshotFile.exists) {
      val serializer = serialization.serializerFor(classOf[Vertex[_, _]])
      val snapshotFileInput = new DataInputStream(new FileInputStream(snapshotFile))
      val buffer = new Array[Byte](maxSerializedSize)
      while (snapshotFileInput.available > 0) {
        val serializedLength = snapshotFileInput.readInt
        assert(serializedLength <= maxSerializedSize)
        val bytesRead = snapshotFileInput.read(buffer, 0, serializedLength)
        assert(bytesRead == serializedLength)
        val vertex = serializer.fromBinary(buffer).asInstanceOf[Vertex[Id, _]]
        addVertex(vertex)
      }
      snapshotFileInput.close
    }
  }

  /**
   * Deletes the worker snapshots if they exist.
   */
  def deleteSnapshot {
    val snapshotFile = new File(s"$workerId.snapshot")
    if (snapshotFile.exists) {
      snapshotFile.delete
    }
  }

  def getWorkerStatusForCoordinator: WorkerStatus = {
    WorkerStatus(
      workerId = workerId,
      timeStamp = System.nanoTime,
      isIdle = isIdle,
      isPaused = isPaused,
      messagesSent = SentMessagesStats(
        messageBus.messagesSentToWorkers,
        messageBus.messagesSentToNodes,
        messageBus.messagesSentToCoordinator + 1, // +1 to account for the status message itself.
        messageBus.messagesSentToOthers),
      messagesReceived = counters.messagesReceived)
  }

  def getWorkerStatusForNode: WorkerStatus = {
    val ws = WorkerStatus(
      workerId = workerId,
      timeStamp = System.nanoTime,
      isIdle = isIdle,
      isPaused = isPaused,
      messagesSent = SentMessagesStats(
        messageBus.messagesSentToWorkers,
        messageBus.messagesSentToNodes,
        messageBus.messagesSentToCoordinator,
        messageBus.messagesSentToOthers),
      messagesReceived = counters.messagesReceived)
    ws.messagesSent.nodes(nodeId) = ws.messagesSent.nodes(nodeId) + 1 // +1 to account for the status message itself.
    ws
  }

  def getIndividualWorkerStatistics: List[WorkerStatistics] = List(getWorkerStatistics)

  def getWorkerStatistics: WorkerStatistics = {
    WorkerStatistics(
      workerId = Some(workerId),
      toSignalSize = vertexStore.toSignal.size,
      toCollectSize = vertexStore.toCollect.size,
      collectOperationsExecuted = counters.collectOperationsExecuted,
      signalOperationsExecuted = counters.signalOperationsExecuted,
      numberOfVertices = vertexStore.vertices.size,
      verticesAdded = counters.verticesAdded,
      verticesRemoved = counters.verticesRemoved,
      numberOfOutgoingEdges = counters.outgoingEdgesAdded - counters.outgoingEdgesRemoved, //only valid if no edges are removed during execution
      outgoingEdgesAdded = counters.outgoingEdgesAdded,
      outgoingEdgesRemoved = counters.outgoingEdgesRemoved,
      heartbeatMessagesReceived = counters.heartbeatMessagesReceived,
      signalMessagesReceived = counters.signalMessagesReceived,
      bulkSignalMessagesReceived = counters.bulkSignalMessagesReceived,
      continueMessagesReceived = counters.continueMessagesReceived,
      requestMessagesReceived = counters.requestMessagesReceived,
      otherMessagesReceived = counters.otherMessagesReceived,
      messagesSentToWorkers = messageBus.messagesSentToWorkers.sum,
      messagesSentToNodes = messageBus.messagesSentToNodes.sum,
      messagesSentToCoordinator = messageBus.messagesSentToCoordinator,
      messagesSentToOthers = messageBus.messagesSentToOthers)
  }

  // TODO: Move this method to Node and use proper node id.
  def getIndividualNodeStatistics: List[NodeStatistics] = List(getNodeStatistics)

  // TODO: Move this method to Node and use proper node id.
  def getNodeStatistics: NodeStatistics = {
    val runtime: Runtime = Runtime.getRuntime
    try {
      val osBean: OperatingSystemMXBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean]);
      NodeStatistics(
        nodeId = Some(workerId),
        os = System.getProperty("os.name"),
        runtime_mem_total = runtime.totalMemory,
        runtime_mem_max = runtime.maxMemory,
        runtime_mem_free = runtime.freeMemory,
        runtime_cores = runtime.availableProcessors,
        jmx_committed_vms = osBean.getCommittedVirtualMemorySize,
        jmx_mem_free = osBean.getFreePhysicalMemorySize,
        jmx_mem_total = osBean.getTotalPhysicalMemorySize,
        jmx_swap_free = osBean.getFreeSwapSpaceSize,
        jmx_swap_total = osBean.getTotalSwapSpaceSize,
        jmx_process_load = osBean.getProcessCpuLoad,
        jmx_process_time = osBean.getProcessCpuTime,
        jmx_system_load = osBean.getSystemCpuLoad)
    } catch {
      case notSupported: NoSuchMethodError =>
        NodeStatistics(
          nodeId = Some(workerId),
          os = System.getProperty("os.name"),
          runtime_mem_total = runtime.totalMemory,
          runtime_mem_max = runtime.maxMemory,
          runtime_mem_free = runtime.freeMemory,
          runtime_cores = runtime.availableProcessors)
    }
  }

  protected def logIntialization {
    if (messageBus.isInitialized) {
      val msg = s"Worker $workerId has a fully initialized message bus."
      log.debug(msg)
      sendStatusToCoordinator
    }
  }

  def registerWorker(otherWorkerId: Int, worker: ActorRef) {
    counters.requestMessagesReceived -= 1 // Registration messages are not counted.
    messageBus.registerWorker(otherWorkerId, worker)
    logIntialization
  }

  def registerNode(nodeId: Int, node: ActorRef) {
    counters.requestMessagesReceived -= 1 // Registration messages are not counted.
    messageBus.registerNode(nodeId, node)
    logIntialization
  }

  def registerCoordinator(coordinator: ActorRef) {
    counters.requestMessagesReceived -= 1 // Registration messages are not counted.
    messageBus.registerCoordinator(coordinator)
    logIntialization
  }

}


