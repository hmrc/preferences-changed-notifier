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
import org.mockito.MockitoSugar.{mock, reset, times, verify, when}
import org.mongodb.scala.bson.ObjectId
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{convertToAnyMustWrapper, equal}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.{
  Digital,
  Paper
}
import uk.gov.hmrc.preferenceschangednotifier.model.{
  PersistenceError,
  PreferencesChanged,
  PreferencesChangedRef
}
import uk.gov.hmrc.preferenceschangednotifier.repository.{
  PreferencesChangedRepository,
  PreferencesChangedWorkItemRepository
}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PreferencesChangedServiceSpec
    extends AnyFreeSpec
    with ScalaFutures
    with BeforeAndAfterEach
    with EitherValues {
  spec =>

  var repo = mock[PreferencesChangedRepository]
  var workItemRepo = mock[PreferencesChangedWorkItemRepository]

  val svc = new PreferencesChangedService(repo, workItemRepo)

  override def beforeEach(): Unit = {
    reset(repo)
  }

  "Preferences changed service" - {

    "should correctly add an item to the repo" in {
      val pId = new ObjectId("75259498e6baf61da75dceef")
      val pcId = new ObjectId("65259498e6baf61da75dceef")

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(
          Future.successful(
            PreferencesChanged(_id = pcId,
                               changedValue = Paper,
                               preferenceId = pId,
                               updatedAt = Instant.now(),
                               Map.empty))
        )

      when(workItemRepo.pushUpdated(any[PreferencesChangedRef]))
        .thenReturn(
          Future.successful(
            WorkItem(
              new ObjectId,
              receivedAt = Instant.now,
              updatedAt = Instant.now,
              availableAt = Instant.now,
              status = ToDo,
              failureCount = 0,
              item = PreferencesChangedRef(pcId, pId, "EpsHodsAdapter")
            )
          )
        )

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId().toString,
        Instant.now,
        taxIds = Map.empty
      )
      val result = svc.preferenceChanged(pcr).value.futureValue

      result must equal(Right(()))
      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "successfully overwrites duplicate" in {
      val pc1 = PreferencesChanged(
        _id = new ObjectId("65259498e6baf61da75dceef"),
        changedValue = Paper,
        preferenceId = new ObjectId("75259498e6baf61da75dceef"),
        updatedAt = Instant.now(),
        Map.empty
      )

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.successful(pc1))

      val pc2 = PreferencesChanged(
        _id = new ObjectId("65259498e6baf61da75dceef"),
        changedValue = Digital,
        preferenceId = new ObjectId("75259498e6baf61da75dceef"),
        updatedAt = Instant.now(),
        Map.empty
      )

      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.successful(pc2))

      val pcr = PreferencesChangedRequest(
        changedValue = Digital,
        preferenceId = pc1.preferenceId.toString,
        Instant.now,
        taxIds = Map.empty
      )

      val result = svc.preferenceChanged(pcr).value.futureValue
      result must equal(Right(()))
      val result2 = svc.preferenceChanged(pcr).value.futureValue
      result2 must equal(Right(()))
      verify(repo, times(2)).upsert(any[PreferencesChanged])
    }

    "fails adding an item to the repo" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenThrow(new RuntimeException("oops"))

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId("75259498e6baf61da75dceef").toString,
        Instant.now,
        taxIds = Map.empty
      )
      val result = svc.preferenceChanged(pcr).value.futureValue

      result must equal(
        Left(PersistenceError("java.lang.RuntimeException: oops")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "fails if the future fails" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenReturn(Future.failed(new RuntimeException("whoa!")))

      val pcr = PreferencesChangedRequest(
        changedValue = Paper,
        new ObjectId().toString,
        Instant.now,
        taxIds = Map.empty
      )
      val result = svc.preferenceChanged(pcr).value.futureValue

      result must equal(
        Left(PersistenceError("java.lang.RuntimeException: whoa!")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }

    "fails if the repo call throws" in {
      when(repo.upsert(any[PreferencesChanged]))
        .thenThrow(new RuntimeException("whoa, throwing!"))

      val pcr = PreferencesChangedRequest(Paper,
                                          new ObjectId().toString,
                                          Instant.now,
                                          Map.empty)

      val result = svc.preferenceChanged(pcr).value.futureValue

      result must equal(
        Left(PersistenceError("java.lang.RuntimeException: whoa, throwing!")))

      verify(repo, times(1)).upsert(any[PreferencesChanged])
    }
  }

}
