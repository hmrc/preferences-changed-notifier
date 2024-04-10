/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.preferenceschangednotifier.service

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.{ mock, reset, times, verify, when }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{ convertToAnyMustWrapper, equal }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.connectors.{ EpsHodsAdapterConnector, UpdatedPrintSuppressionsConnector }
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.{ Digital, Paper }
import uk.gov.hmrc.preferenceschangednotifier.model.{ PersistenceError, PreferencesChanged, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.repository.{ PreferencesChangedRepository, PreferencesChangedWorkItemRepository }

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreferencesChangedServiceSpec extends AnyFreeSpec with ScalaFutures with BeforeAndAfterEach with EitherValues {
  spec =>

  var repo = mock[PreferencesChangedRepository]
  var workItemRepo = mock[PreferencesChangedWorkItemRepository]
  var epsConnector = mock[EpsHodsAdapterConnector]
  var upsConnector = mock[UpdatedPrintSuppressionsConnector]
  var subscribers = Seq(epsConnector, upsConnector)

  when(epsConnector.name).thenReturn("EpsHodsAdapter")
  when(upsConnector.name).thenReturn("UpdatedPrintSuppressions")

  val svc = new PreferencesChangedService(repo, workItemRepo, subscribers)

  override def beforeEach(): Unit = {
    reset(repo)
    reset(workItemRepo)
  }

  "Preferences changed service" - {
    val entityId = UUID.randomUUID().toString

    "should correctly add an item to the repo, but NOT add a workitem" in {
      val pId = new ObjectId("75259498e6baf61da75dceef")
      val pcId = new ObjectId("65259498e6baf61da75dceef")

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(
          Future.successful(
            PreferencesChanged(
              _id = pcId,
              entityId,
              changedValue = Paper,
              preferenceId = pId,
              updatedAt = Instant.now(),
              Map.empty
            )
          )
        )

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId().toString,
        entityId,
        Instant.now,
        taxIds = Map.empty
      )
      val result =
        svc.preferenceChanged(pcr).value.futureValue

      result must equal(Right(()))
      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "successfully overwrites duplicate" in {
      val pc1 = PreferencesChanged(
        _id = new ObjectId("65259498e6baf61da75dceef"),
        entityId,
        changedValue = Paper,
        preferenceId = new ObjectId("75259498e6baf61da75dceef"),
        updatedAt = Instant.now(),
        Map("nino" -> "AB112233A")
      )

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.successful(pc1))

      val pc2 = PreferencesChanged(
        _id = new ObjectId("65259498e6baf61da75dceef"),
        entityId,
        changedValue = Digital,
        preferenceId = new ObjectId("75259498e6baf61da75dceef"),
        updatedAt = Instant.now(),
        Map("nino" -> "AB112233A")
      )

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.successful(pc2))

      val pcr1 =
        PreferencesChangedRef(pc1._id, pc1.preferenceId, entityId, "EpsHodsAdapter")
      when(workItemRepo.pushUpdated(any[PreferencesChangedRef]))
        .thenReturn(
          Future.successful(
            WorkItem(
              item = pcr1,
              id = new ObjectId(),
              receivedAt = Instant.now(),
              updatedAt = Instant.now(),
              availableAt = Instant.now(),
              status = ToDo,
              failureCount = 0
            )
          )
        )

      val pcr = PreferencesChangedRequest(
        changedValue = Digital,
        preferenceId = pc1.preferenceId.toString,
        entityId,
        Instant.now,
        taxIds = Map("nino" -> "AB112233A")
      )

      when(epsConnector.taxIdsValid(any[Map[String, String]])).thenReturn(true)

      val result =
        svc.preferenceChanged(pcr).value.futureValue
      result must equal(Right(()))

      val result2 =
        svc.preferenceChanged(pcr).value.futureValue
      result2 must equal(Right(()))

      verify(repo, times(2)).upsert(any[PreferencesChanged])
      verify(workItemRepo, times(2)).pushUpdated(any[PreferencesChangedRef])
    }

    "adds only a single workitem is updateUPS is false" in {
      val pc = PreferencesChanged(
        _id = new ObjectId("65259498e6baf61da75dceef"),
        entityId,
        changedValue = Paper,
        preferenceId = new ObjectId("75259498e6baf61da75dceef"),
        updatedAt = Instant.now(),
        Map.empty
      )

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.successful(pc))

      val pcr = PreferencesChangedRequest(
        changedValue = Digital,
        preferenceId = pc.preferenceId.toString,
        entityId,
        Instant.now,
        taxIds = Map.empty
      )

      val pcref =
        PreferencesChangedRef(pc._id, pc.preferenceId, entityId, "EpsHodsAdapter")
      when(workItemRepo.pushUpdated(any[PreferencesChangedRef]))
        .thenReturn(
          Future.successful(
            WorkItem(
              item = pcref,
              id = new ObjectId(),
              receivedAt = Instant.now(),
              updatedAt = Instant.now(),
              availableAt = Instant.now(),
              status = ToDo,
              failureCount = 0
            )
          )
        )

      val result =
        svc.preferenceChanged(pcr).value.futureValue
      result must equal(Right(()))

      verify(workItemRepo, times(1)).pushUpdated(any[PreferencesChangedRef])
      verify(workItemRepo, times(1))
        .pushUpdated(any[PreferencesChangedRef]) // UPS is false
    }

    "fails adding an item to the repo" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenThrow(new RuntimeException("oops"))

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId("75259498e6baf61da75dceef").toString,
        entityId,
        Instant.now,
        taxIds = Map.empty
      )
      val result =
        svc.preferenceChanged(pcr).value.futureValue

      result must equal(Left(PersistenceError("java.lang.RuntimeException: oops")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "fails if the future fails" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.failed(new RuntimeException("whoa!")))

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId().toString,
        entityId,
        Instant.now,
        taxIds = Map.empty
      )
      val result =
        svc.preferenceChanged(pcr).value.futureValue

      result must equal(Left(PersistenceError("java.lang.RuntimeException: whoa!")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "fails if the repo call throws" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenThrow(new RuntimeException("whoa, throwing!"))

      val pcr = PreferencesChangedRequest(Paper, new ObjectId().toString, entityId, Instant.now, Map.empty)

      val result =
        svc.preferenceChanged(pcr).value.futureValue

      result must equal(Left(PersistenceError("java.lang.RuntimeException: whoa, throwing!")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }
  }

}
