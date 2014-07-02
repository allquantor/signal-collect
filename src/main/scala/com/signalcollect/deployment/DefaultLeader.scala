/**
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

import scala.async.Async.async
import scala.concurrent.ExecutionContext.Implicits.global
import com.signalcollect.configuration.ActorSystemRegistry
import com.typesafe.config.Config
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import com.signalcollect.util.AkkaRemoteAddress

class DefaultLeader(
  akkaConfig: Config,
  deploymentConfig: DeploymentConfiguration) extends Leader {
  println(deploymentConfig)
  val system = ActorSystemRegistry.retrieve("SignalCollect").getOrElse(startActorSystem)
  val leaderactor = system.actorOf(Props(classOf[LeaderActor], this), "leaderactor")
  val leaderAddress = AkkaRemoteAddress.get(leaderactor, system)
  private var executionStarted = false
  private var executionFinished = false
  private var nodeActors: List[ActorRef] = Nil
  private var shutdownAddresses: List[ActorRef] = Nil

  def isExecutionStarted = executionStarted
  def isExecutionFinished = executionFinished

  /**
   * starts the lifecycle of the leader and an execution. This method is Non-Blocking
   */
  def start {
    async {
      waitForAllNodeContainers
      startExecution
      executionFinished = true
      shutdown
    }
  }

  /**
   * waits till enough NodeContainers are registered
   */
  def waitForAllNodeContainers {
    while (!allNodeContainersRunning) {
      Thread.sleep(100)
    }
  }

  /**
   * starts the algorithm given in the DeploymentConfiguration on the registered NodeActors
   */
  def startExecution {
    executionStarted = true
    val algorithm = deploymentConfig.algorithm
    val parameters = deploymentConfig.algorithmParameters
    val nodeActors = getNodeActors.toArray
    val clazz = Class.forName(algorithm)
    val algorithmObject = clazz.getField("MODULE$").get(classOf[DeployableAlgorithm]).asInstanceOf[DeployableAlgorithm]
//    val algorithmObject = Class.forName(algorithm).newInstance.asInstanceOf[DeployableAlgorithm]
    println(s"start algorithm: $algorithm")
    algorithmObject.lifecycle(parameters, Some(nodeActors), Some(system))
  }

  /**
   * tells all NodeContainers to shutdown and then terminates the ActorSystem
   */
  def shutdown {
    try {
      val shutdownActor = getShutdownActors.foreach(_ ! "shutdown")
      Thread.sleep(10000)
    } finally {
      if (!system.isTerminated) {
        system.shutdown
        system.awaitTermination
        ActorSystemRegistry.remove(system)
      }
    }
  }

  /**
   * starts an ActorSystem with the name SignalCollect and registers it, in the ActorSystemRegistry
   */
  def startActorSystem: ActorSystem = {
    val system = ActorSystem("SignalCollect", akkaConfig)
    ActorSystemRegistry.register(system)
    system
  }

  /**
   * checks if enough nodes are registered to fulfill the needs in the deploymentConfiguration
   */
  def allNodeContainersRunning: Boolean = {
    getNumberOfRegisteredNodes == deploymentConfig.numberOfNodes

  }

  def getNodeActors: List[ActorRef] = {
    synchronized {
      nodeActors
    }
  }
  
  def getShutdownActors: List[ActorRef] = {
    shutdownAddresses
  }
  
  def addNodeActorAddress(address: String) {
    synchronized {
      nodeActors = system.actorFor(address) :: nodeActors
    }
  }
  
  def addShutdownAddress(address: String) {
    synchronized {
      shutdownAddresses = system.actorFor(address) :: shutdownAddresses
    }
  }
  def getNumberOfRegisteredNodes: Int = {
    nodeActors.size
  }
  
  def clear = {
    synchronized {
      nodeActors = Nil
      shutdownAddresses = Nil
    }
  }

}

class LeaderActor(leader: DefaultLeader) extends Actor {
  override def receive = {
    case address: String => filterAddress(address)
    case _ => println("received unexpected message")
  }

  def filterAddress(address: String) {
    address match {
      case nodeactor if nodeactor.contains("NodeActor") => leader.addNodeActorAddress(address)
      case shutdown if shutdown.contains("shutdown") => leader.addShutdownAddress(address)
      case _ =>
    }
  }
}