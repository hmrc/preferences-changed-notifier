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

import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{ reset, times, verify, verifyNoInteractions, when }
import org.mockito.ArgumentMatchers.any
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{ equal, must }
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.scalacheck.*
import play.api.http.Status.{ BAD_REQUEST, CONFLICT, FORBIDDEN, INTERNAL_SERVER_ERROR, OK, TOO_MANY_REQUESTS, UNAUTHORIZED }
import uk.gov.hmrc.http.{ HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, PermanentlyFailed, ToDo }
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Failure
import uk.gov.hmrc.preferenceschangednotifier.connectors.{ EpsHodsAdapterConnector, UpdatedPrintSuppressionsConnector }
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Digital
import uk.gov.hmrc.preferenceschangednotifier.model.{ EntityId, NotifySubscriberRequest, PreferencesChangedRef, Result }

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PublishSubscribersPublisherSpec
    extends AnyFreeSpec with ScalaFutures with BeforeAndAfterEach with EitherValues with ScalaCheckPropertyChecks {
  spec =>

  var preferencesChangedService = mock[PreferencesChangedService]
  var epsConnector = mock[EpsHodsAdapterConnector]
  var upsConnector = mock[UpdatedPrintSuppressionsConnector]
  var auditConnector = mock[AuditConnector]
  var subscribers = Seq(epsConnector, upsConnector)

  override def beforeEach(): Unit = {
    reset(epsConnector)
    reset(upsConnector)
    reset(auditConnector)
    reset(preferencesChangedService)
    when(epsConnector.name).thenReturn("EpsHodsAdapter")
    when(upsConnector.name).thenReturn("UpdatedPrintSuppressions")
  }

  private def createWorkItem = {
    val preferenceChangedId = new ObjectId()
    val preferenceId = new ObjectId()
    val workItemId = new ObjectId()
    val entityId = EntityId.generate()

    val pcr1 = PreferencesChangedRef(preferenceChangedId, preferenceId, entityId, "EpsHodsAdapter")
    WorkItem(
      item = pcr1,
      id = workItemId,
      receivedAt = Instant.now(),
      updatedAt = Instant.now(),
      availableAt = Instant.now(),
      status = ToDo,
      failureCount = 0
    )
  }

  "publish subscribers publisher" - {

    "should complete with success for 200 OK" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers, auditConnector)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"), false)

      val workItem = createWorkItem

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Right(HttpResponse(OK, "", Map.empty))))
      when(preferencesChangedService.completeAndDelete(any))
        .thenReturn(Future(true))

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Right(Result(s"Completed & deleted workitem: ${workItem.id} successfully: HttpResponse status=200"))
      )
      verifyNoInteractions(auditConnector)
    }

    "should complete with retry Failure for each retryable 4XX status" in {
      val retryableCombos =
        Table("status", CONFLICT, TOO_MANY_REQUESTS, UNAUTHORIZED, FORBIDDEN)

      forAll(retryableCombos) { (status: Int) =>
        reset(preferencesChangedService, auditConnector)

        val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers, auditConnector)
        val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"), false)

        when(epsConnector.notifySubscriber(any)(any, any))
          .thenReturn(Future(Left(UpstreamErrorResponse("Oops", TOO_MANY_REQUESTS, 0, Map.empty))))
        when(preferencesChangedService.completeWithStatus(any, any))
          .thenReturn(Future(true))
        when(auditConnector.sendExtendedEvent(any)(any, any)).thenReturn(Future(Failure("Fail")))

        val workItem = createWorkItem
        val response = svc.execute(req, workItem).futureValue
        response must equal(
          Left(
            s"Publish to subscriber EpsHodsAdapter" +
              s" failed returning [Oops], will retry. Workitem updated true"
          )
        )
        verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(Failed))
        verify(auditConnector).sendExtendedEvent(any)(any, any)
      }
    }

    "should complete with Failure for 429 TOO_MANY_REQUESTS if retry limit is reached" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers, auditConnector)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"), false)

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", TOO_MANY_REQUESTS, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))
      when(auditConnector.sendExtendedEvent(any)(any, any)).thenReturn(Future(Failure("Fail")))

      val workItem = createWorkItem.copy(failureCount = 10)

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          s"Publish to subscriber EpsHodsAdapter failed to publish workItem: [${workItem.id}]" +
            s" 10 times, marking as permanently failed\nError: " +
            s"Oops. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(PermanentlyFailed))
      verify(auditConnector).sendExtendedEvent(any)(any, any)
    }

    // unrecoverable for any other 4XX
    "should complete with PermanentFailure for 400 BAD_REQUEST" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers, auditConnector)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"), false)

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", BAD_REQUEST, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))
      when(auditConnector.sendExtendedEvent(any)(any, any)).thenReturn(Future(Failure("Fail")))

      val workItem = createWorkItem

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          "publish to subscriber EpsHodsAdapter permanently failed returning [Oops]. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(PermanentlyFailed))
      verify(auditConnector).sendExtendedEvent(any)(any, any)
    }

    // recoverable 5XX
    "should complete with retryable Failure for 500 INTERNAL_SERVER_ERROR" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers, auditConnector)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"), false)

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", INTERNAL_SERVER_ERROR, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))
      when(auditConnector.sendExtendedEvent(any)(any, any)).thenReturn(Future(Failure("Fail")))

      val workItem = createWorkItem

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          "Publish to subscriber EpsHodsAdapter failed returning [Oops], will retry. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(Failed))
      verify(auditConnector).sendExtendedEvent(any)(any, any)
    }

  }
}
