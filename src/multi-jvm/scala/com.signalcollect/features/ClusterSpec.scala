/*
 *  @author Bharath Kumar
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
 */

package com.signalcollect.features

import akka.actor.{ActorRef, Props}
import akka.cluster.Cluster
import akka.pattern.ask
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import akka.util.Timeout
import com.signalcollect.nodeprovisioning.cluster.{ClusterNodeProvisionerActor, RetrieveNodeActors}
import com.signalcollect.util.TestAnnouncements
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.language.postfixOps

trait STMultiNodeSpec extends MultiNodeSpecCallbacks with WordSpecLike with ShouldMatchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()
}

class ClusterSpecMultiJvmNode1 extends ClusterSpec

class ClusterSpecMultiJvmNode2 extends ClusterSpec

class ClusterSpecMultiJvmNode3 extends ClusterSpec

object MultiNodeTestConfig extends MultiNodeConfig {
  val provisioner = role("provisioner")
  val worker1 = role("worker1")
  val worker2 = role("worker2")

  val nodeConfig = ConfigFactory.load()
  val seedIp = nodeConfig.getString("akka.clustering.seed-ip")
  val seedPort = nodeConfig.getInt("akka.clustering.seed-port")
  val clusterName = "ClusterSpec"

  nodeConfig(provisioner) {
    ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$seedPort")
  }

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString(s"""akka.cluster.seed-nodes=["akka.tcp://"${clusterName}"@"${seedIp}":"${seedPort}]""")
    .withFallback(ConfigFactory.load()))
}

class ClusterSpec extends MultiNodeSpec(MultiNodeTestConfig) with STMultiNodeSpec
with ImplicitSender with TestAnnouncements with ScalaFutures {

  import MultiNodeTestConfig._

  override def initialParticipants = roles.size

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(1000, Millis)))

  val masterAddress = node(provisioner).address
  val worker1Address = node(worker1).address
  val worker2Address = node(worker2).address
  val workers = 3
  val idleDetectionPropagationDelayInMilliseconds = 500

  muteDeadLetters(classOf[Any])(system)

  "SignalCollect" should {
    "get the cluster up" in {
      runOn(provisioner) {
        system.actorOf(Props(classOf[ClusterNodeProvisionerActor], idleDetectionPropagationDelayInMilliseconds,
          "ClusterMasterBootstrap", workers), "ClusterMasterBootstrap")
      }
      enterBarrier("all nodes are up")

      runOn(worker1) {
        Cluster(system).join(worker1Address)
      }
      enterBarrier("worker1 started")

      runOn(worker2) {
        Cluster(system).join(worker2Address)
      }
      enterBarrier("worker2 started")

      runOn(provisioner) {
        implicit val timeout = Timeout(300.seconds)
        val masterActor = system.actorSelection(node(provisioner) / "user" / "ClusterMasterBootstrap")
        val nodeActorsFuture = (masterActor ? RetrieveNodeActors).mapTo[Array[ActorRef]]
        whenReady(nodeActorsFuture) { nodeActors =>
          assert(nodeActors.size == workers)
        }
      }
      enterBarrier("all done!")
    }
  }

  override def beforeAll: Unit = multiNodeSpecBeforeAll()

  override def afterAll: Unit = multiNodeSpecAfterAll()
}