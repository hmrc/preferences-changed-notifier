/*
 * Copyright 2025 HM Revenue & Customs
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

import org.apache.pekko.actor.ActorSystem
import org.bson.codecs.ObjectIdGenerator
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.{ eq => meq }
import org.mockito.ArgumentMatchers.{ any, contains }
import org.mockito.Mockito.{ reset, times, verify, verifyNoInteractions, verifyNoMoreInteractions, when }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.{ Seconds, Span }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.domain.Generator
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ InProgress, PermanentlyFailed, ToDo }
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.preferenceschangednotifier.config.PublishSubscribersServiceConfig
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.{ EntityId, PreferencesChanged, PreferencesChangedRef, Result }

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class PublishSubscribersServiceSpec extends AnyFreeSpec with ScalaFutures with BeforeAndAfterEach {

  private val pcService: PreferencesChangedService = mock[PreferencesChangedService]
  private val publisher: PublishSubscribersPublisher = mock[PublishSubscribersPublisher]
  private val auditConnector: AuditConnector = mock[AuditConnector]
  private val lockRepo: LockRepository = mock[LockRepository]
  private val lifecycle: ApplicationLifecycle = mock[ApplicationLifecycle]

  private implicit val system: ActorSystem = ActorSystem("Test")

  private val EPS_HODS_ADAPTER = "EpsHodsAdapter"
  private val UPDATED_PRINT_SUPPRESSION = "UpdatedPrintSuppression"

  val config: Configuration = Configuration.from(
    Map(
      "scheduling.PublishSubscribersJob.taskEnabled" -> false,
      "preferencesChanged.retryFailedAfter"          -> "15minutes",
      "preferencesChanged.rateLimit.elements"        -> "10",
      "preferencesChanged.rateLimit.per"             -> "1second"
    )
  )
  val cfg = new PublishSubscribersServiceConfig(config)

  override def beforeEach(): Unit = {
    reset(pcService)
    reset(publisher)
    reset(auditConnector)
    reset(lockRepo)
    reset(lifecycle)
  }

  "publish subscribers service" - {
    val svc = new PublishSubscribersService(pcService, publisher, auditConnector, lockRepo, lifecycle, cfg)

    "test execute stream with no workItems" in {
      // No workItems, stops the stream
      when(pcService.pull(any)).thenReturn(Future.successful(None))

      svc.execute().futureValue

      verify(pcService).pull(any)
      verifyNoMoreInteractions(pcService)
      verifyNoInteractions(publisher)
    }

    "test execute stream with one workItem" in {
      val (preferencesChanged, workItem) = create(EPS_HODS_ADAPTER)

      when(pcService.pull(any))
        .thenReturn(Future.successful(Some(workItem)))
        .thenReturn(Future.successful(None)) // Stops the stream

      when(pcService.find(ArgumentMatchers.eq(workItem.item.preferenceChangedId)))
        .thenReturn(Future.successful(Right(preferencesChanged)))
      when(publisher.execute(any, any))
        .thenReturn(Future.successful(Right(Result("Result1"))))

      svc.execute().futureValue

      verify(pcService, times(2)).pull(any) // 1. Workitem, 2. Stops stream
      verify(pcService).find(any)
      verify(publisher).execute(any, any)
    }

    "test execute stream with two workItems" in {
      val (preferencesChanged1, workItem1) = create(EPS_HODS_ADAPTER)
      val (preferencesChanged2, workItem2) = create(UPDATED_PRINT_SUPPRESSION)

      when(pcService.pull(any))
        .thenReturn(Future.successful(Some(workItem1)))
        .thenReturn(Future.successful(Some(workItem2)))
        .thenReturn(Future.successful(None)) // Stops the stream

      when(pcService.find(ArgumentMatchers.eq(workItem1.item.preferenceChangedId)))
        .thenReturn(Future.successful(Right(preferencesChanged1)))
      when(pcService.find(ArgumentMatchers.eq(workItem2.item.preferenceChangedId)))
        .thenReturn(Future.successful(Right(preferencesChanged2)))

      when(publisher.execute(any, any))
        .thenReturn(Future.successful(Right(Result("Result1"))))
        .thenReturn(Future.successful(Right(Result("Result2"))))

      svc.execute().futureValue

      verify(pcService, times(3)).pull(any) // 1. Workitem1, 2. Workitem2, 3. Stops stream
      verify(pcService, times(2)).find(any)
      verify(publisher, times(2)).execute(any, any)
    }

    "test execute stream with one workItem having no preferencesChanged item" in {
      val (preferencesChanged, workItem) = create(EPS_HODS_ADAPTER)

      when(pcService.pull(any))
        .thenReturn(Future.successful(Some(workItem)))
        .thenReturn(Future.successful(None)) // Stops the stream

      when(pcService.find(ArgumentMatchers.eq(workItem.item.preferenceChangedId)))
        .thenReturn(Future.successful(Left(s"_id ${workItem.item.preferenceChangedId} not found")))

      svc.execute().futureValue

      verify(pcService).find(any)
      verify(pcService).completeWithStatus(meq(workItem), meq(PermanentlyFailed))
      verifyNoInteractions(publisher)
    }
  }

  private def create(subscriber: String) = {
    val preferencesChanged = PreferencesChanged(
      _id = new ObjectId(),
      entityId = EntityId.generate(),
      changedValue = Paper,
      preferenceId = new ObjectId(),
      updatedAt = Instant.now(),
      Map.empty,
      true
    )

    val pcRef = PreferencesChangedRef(
      preferencesChanged._id,
      preferencesChanged.preferenceId,
      preferencesChanged.entityId,
      subscriber
    )

    (
      preferencesChanged,
      WorkItem(
        item = pcRef,
        id = new ObjectId(),
        receivedAt = Instant.now(),
        updatedAt = Instant.now(),
        availableAt = Instant.now(),
        status = InProgress,
        failureCount = 0
      )
    )
  }
}
