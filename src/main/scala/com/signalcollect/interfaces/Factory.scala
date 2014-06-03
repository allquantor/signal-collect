/*
 *  @author Philip Stutz
 *
 *  Copyright 2011 University of Zurich
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

import scala.reflect.ClassTag
import com.signalcollect.factory.workerapi.DefaultWorkerApiFactory
import akka.actor.ActorSystem

abstract class Factory extends Serializable

abstract class WorkerFactory extends Factory {
  def createInstance[Id: ClassTag, Signal: ClassTag](
    workerId: Int,
    numberOfWorkers: Int,
    numberOfNodes: Int,
    messageBusFactory: MessageBusFactory,
    mapperFactory: MapperFactory,
    storageFactory: StorageFactory,
    schedulerFactory: SchedulerFactory,
    heartbeatIntervalInMilliseconds: Int): WorkerActor[Id, Signal]
}

abstract class MessageBusFactory extends Factory {
  def createInstance[Id: ClassTag, Signal: ClassTag](
    system: ActorSystem,
    numberOfWorkers: Int,
    numberOfNodes: Int,
    mapper: VertexToWorkerMapper[Id],
    sendCountIncrementorForRequests: MessageBus[_, _] => Unit,
    workerApiFactory: WorkerApiFactory = DefaultWorkerApiFactory): MessageBus[Id, Signal]
}

abstract class MapperFactory extends Factory {
  def createInstance[Id](numberOfNodes: Int, workersPerNode: Int): VertexToWorkerMapper[Id]
}

abstract class StorageFactory extends Factory {
  def createInstance[Id]: Storage[Id]
}

abstract class SchedulerFactory extends Factory {
  def createInstance[Id](worker: Worker[Id, _]): Scheduler[Id]
}

abstract class WorkerApiFactory extends Factory {
  def createInstance[Id, Signal](
    workerProxies: Array[WorkerApi[Id, Signal]],
    mapper: VertexToWorkerMapper[Id]): WorkerApi[Id, Signal]
}
