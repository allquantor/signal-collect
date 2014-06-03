/*
 *  @author Philip Stutz
 *  @author Mihaela Verman
 *  @author Francisco de Freitas
 *  @author Daniel Strebel
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

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Queue
import java.lang.management.ManagementFactory
import scala.Array.canBuildFrom
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.language.reflectiveCalls
import scala.reflect.ClassTag
import com.signalcollect._
import com.signalcollect.interfaces._
import com.signalcollect.serialization.DefaultSerializer
import com.sun.management.OperatingSystemMXBean
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ReceiveTimeout
import akka.dispatch.MessageQueue
import akka.actor.Actor
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

case object ReportStatusToCoordinator

case class StartPingPongExchange(pingPongPartner: Int)

case class Ping(fromWorker: Int)
case class Pong(fromWorker: Int)

case class ScheduleOperations(timestamp: Long)

/**
 * Incrementor function needs to be defined in its own class to prevent unnecessary
 * closure capture when serialized.
 */
case class IncrementorForWorker(workerId: Int) {
  def increment(messageBus: MessageBus[_, _]) = {
    messageBus.incrementMessagesSentToWorker(workerId)
  }
}

/**
 * Class that interfaces the worker implementation with Akka messaging.
 * Mainly responsible for translating received messages to function calls on a worker implementation.
 */
class AkkaWorker[@specialized(Int, Long) Id: ClassTag, @specialized(Int, Long, Float, Double) Signal: ClassTag](
  val workerId: Int,
  val numberOfWorkers: Int,
  val numberOfNodes: Int,
  val messageBusFactory: MessageBusFactory,
  val mapperFactory: MapperFactory,
  val storageFactory: StorageFactory,
  val schedulerFactory: SchedulerFactory,
  val reportingIntervalInMilliseconds: Int,
  val pingPongSchedulingIntervalInMilliseconds: Int = 50)
  extends WorkerActor[Id, Signal]
  with ActorLogging
  with ActorRestartLogging {

  def getRandomPingPongPartner = Random.nextInt(numberOfWorkers)
  var pingSentTimestamp: Long = _

  context.system.scheduler.scheduleOnce(pingPongSchedulingIntervalInMilliseconds.milliseconds, self, StartPingPongExchange(getRandomPingPongPartner))

  context.system.scheduler.schedule(reportingIntervalInMilliseconds.milliseconds, reportingIntervalInMilliseconds.milliseconds, self, ReportStatusToCoordinator)

  override def postStop {
    log.debug(s"Worker $workerId has stopped.")
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    val msg = s"Worker $workerId crashed with ${reason.toString} because of ${reason.getCause} or reason ${reason.getMessage} at position ${reason.getStackTraceString}, not recoverable."
    println(msg)
    log.error(msg)
    context.stop(self)
  }

  val messageBus: MessageBus[Id, Signal] = {
    messageBusFactory.createInstance[Id, Signal](
      numberOfWorkers,
      numberOfNodes,
      mapperFactory.createInstance(numberOfNodes, numberOfWorkers / numberOfNodes),
      IncrementorForWorker(workerId).increment _)
  }

  val worker = new WorkerImplementation[Id, Signal](
    workerId = workerId,
    messageBus = messageBus,
    log = log,
    storageFactory = storageFactory,
    schedulerFactory = schedulerFactory,
    signalThreshold = 0.01,
    collectThreshold = 0.0,
    existingVertexHandler = (vOld, vNew, ge) => (),
    undeliverableSignalHandler = (s: Signal, tId: Id, sId: Option[Id], ge: GraphEditor[Id, Signal]) => {
      throw new Exception(s"Undeliverable signal: $s from $sId could not be delivered to $tId.")
      Unit
    },
    edgeAddedToNonExistentVertexHandler = (edge: Edge[Id], vertexId: Id) => {
      throw new Exception(
        s"Could not add edge: ${edge.getClass.getSimpleName}(id = $vertexId -> ${edge.targetId}), because vertex with id $vertexId does not exist.")
      None
    })

  /**
   * How many graph modifications this worker will execute in one batch.
   */
  val graphModificationBatchProcessingSize = 100

  def isInitialized = messageBus.isInitialized

  def applyPendingGraphModifications {
    if (!worker.pendingModifications.isEmpty) {
      try {
        for (modification <- worker.pendingModifications.take(graphModificationBatchProcessingSize)) {
          modification(worker.graphEditor)
        }
      } catch {
        case t: Throwable =>
          println(s"Worker $workerId had a problem during graph loading: ${t.toString}")
          println(t.getStackTrace.mkString("\n"))
      }
      worker.messageBusFlushed = false
    }
  }

  def scheduleOperations {
    worker.setIdle(false)
    self ! ScheduleOperations(System.nanoTime)
    worker.allWorkDoneWhenContinueSent = worker.isAllWorkDone
    worker.operationsScheduled = true
  }

  val messageQueue: Queue[_] = context.asInstanceOf[{ def mailbox: { def messageQueue: MessageQueue } }].mailbox.messageQueue.asInstanceOf[{ def queue: Queue[_] }].queue

  /**
   * This method gets executed when the Akka actor receives a message.
   */
  def receive = {
    case s: SignalMessage[Id, Signal] =>
      worker.counters.signalMessagesReceived += 1
      worker.processSignal(s.signal, s.targetId, s.sourceId)
      if (!worker.operationsScheduled && !worker.isPaused) {
        scheduleOperations
      }

    case bulkSignal: BulkSignal[Id, Signal] =>
      worker.counters.bulkSignalMessagesReceived += 1
      val size = bulkSignal.signals.length
      var i = 0
      while (i < size) {
        val sourceId = bulkSignal.sourceIds(i)
        if (sourceId != null) {
          worker.processSignal(bulkSignal.signals(i), bulkSignal.targetIds(i), Some(sourceId))
        } else {
          worker.processSignal(bulkSignal.signals(i), bulkSignal.targetIds(i), None)
        }
        i += 1
      }
      if (!worker.operationsScheduled && !worker.isPaused) {
        scheduleOperations
      }

    case bulkSignal: BulkSignalNoSourceIds[Id, Signal] =>
      worker.counters.bulkSignalMessagesReceived += 1
      val size = bulkSignal.signals.length
      var i = 0
      while (i < size) {
        worker.processSignal(bulkSignal.signals(i), bulkSignal.targetIds(i), None)
        i += 1
      }
      if (!worker.operationsScheduled && !worker.isPaused) {
        scheduleOperations
      }

    case AddVertex(vertex) =>
      // TODO: More precise accounting for this kind of message.
      worker.counters.requestMessagesReceived += 1
      worker.addVertex(vertex.asInstanceOf[Vertex[Id, _]])
      // TODO: Reevaluate, if we really need to schedule operations.
      if (!worker.operationsScheduled && (!worker.isIdle || !worker.isAllWorkDone)) {
        scheduleOperations
      }

    case AddEdge(sourceVertexId, edge) =>
      // TODO: More precise accounting for this kind of message.
      worker.counters.requestMessagesReceived += 1
      worker.addEdge(sourceVertexId.asInstanceOf[Id], edge.asInstanceOf[Edge[Id]])
      // TODO: Reevaluate, if we really need to schedule operations.
      if (!worker.operationsScheduled && (!worker.isIdle || !worker.isAllWorkDone)) {
        scheduleOperations
      }

    case ReportStatusToCoordinator =>
      if (messageBus.isInitialized) {
        worker.reportStatusToCoordinator
      }

    case ScheduleOperations(timestamp) =>
      if (worker.allWorkDoneWhenContinueSent && worker.isAllWorkDone) {
        worker.setIdle(true)
        worker.operationsScheduled = false
      } else {
        val largeInboxSize = System.nanoTime - timestamp > 100000000 // 100 milliseconds
        if (largeInboxSize) {
          println(s"Schedule operations for $workerId took too long.")
        }
        if (worker.isPaused) {
          if (!largeInboxSize && !worker.systemOverloaded) {
            applyPendingGraphModifications
          } else {
            println("WTF1")
          }
        } else {
          worker.scheduler.executeOperations(largeInboxSize || worker.systemOverloaded)
        }
        if (!worker.messageBusFlushed) {
          messageBus.flush
          worker.messageBusFlushed = true
        }
        scheduleOperations
      }

    case Request(command, reply, incrementor) =>
      worker.counters.requestMessagesReceived += 1
      try {
        val result = command.asInstanceOf[WorkerApi[Id, Signal] => Any](worker)
        if (reply) {
          incrementor(messageBus)
          if (result == null) { // Netty does not like null messages: org.jboss.netty.channel.socket.nio.NioWorker - WARNING: Unexpected exception in the selector loop. - java.lang.NullPointerException
            messageBus.sendToActor(sender, None)
          } else {
            messageBus.sendToActor(sender, result)
          }
        }
      } catch {
        case t: Throwable =>
          val msg = s"Problematic request on worker $workerId: ${t.getStackTraceString}"
          println(msg)
          log.debug(msg)
          throw t
      }
      if (!worker.operationsScheduled && (!worker.isIdle || !worker.isAllWorkDone)) {
        scheduleOperations
      }

    //    case Heartbeat(maySignal) =>
    //      worker.counters.heartbeatMessagesReceived += 1
    //      worker.systemOverloaded = !maySignal

    case StartPingPongExchange(partner) =>
      pingSentTimestamp = System.nanoTime
      messageBus.sendToWorker(partner, Ping(workerId))

    case Ping(fromWorker) =>
      messageBus.sendToWorker(fromWorker, Pong(workerId))

    case Pong(fromWorker) =>
      val exchangeDuration = System.nanoTime - pingSentTimestamp
      if (System.nanoTime - pingSentTimestamp > 50000000) { // 50 milliseconds
        println(s"Exchange between $workerId and $fromWorker took too long.")
        worker.systemOverloaded = true
        pingSentTimestamp = System.nanoTime
        // Immediately play ping-pong with the same slow partner again.
        messageBus.sendToWorker(fromWorker, Ping(workerId))
      } else {
        worker.systemOverloaded = false
        // Wait a bit and then play ping pong with another random partner.
        context.system.scheduler.scheduleOnce(pingPongSchedulingIntervalInMilliseconds.milliseconds, self, StartPingPongExchange(getRandomPingPongPartner))
      }

    case other =>
      worker.counters.otherMessagesReceived += 1
      val msg = s"Worker $workerId could not handle message $other"
      println(msg)
      log.error(msg)
      throw new UnsupportedOperationException(s"Unsupported message: $other")
  }

}
