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
import org.mockito.Mockito.{ reset, times, verify, when }
import org.mockito.ArgumentMatchers.any
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{ equal, must }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{ BAD_REQUEST, INTERNAL_SERVER_ERROR, OK, TOO_MANY_REQUESTS }
import uk.gov.hmrc.http.{ HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, PermanentlyFailed, ToDo }
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.connectors.{ EpsHodsAdapterConnector, UpdatedPrintSuppressionsConnector }
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Digital
import uk.gov.hmrc.preferenceschangednotifier.model.{ NotifySubscriberRequest, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PublishSubscribersPublisherSpec extends AnyFreeSpec with ScalaFutures with BeforeAndAfterEach with EitherValues {
  spec =>

  var preferencesChangedService = mock[PreferencesChangedService]
  var epsConnector = mock[EpsHodsAdapterConnector]
  var upsConnector = mock[UpdatedPrintSuppressionsConnector]
  var subscribers = Seq(epsConnector, upsConnector)

  override def beforeEach(): Unit = {
    reset(epsConnector)
    reset(upsConnector)
    reset(preferencesChangedService)
    when(epsConnector.name).thenReturn("EpsHodsAdapter")
    when(upsConnector.name).thenReturn("UpdatedPrintSuppressions")
  }

  private def createWorkItem = {
    val preferenceChangedId = new ObjectId()
    val preferenceId = new ObjectId()
    val workItemId = new ObjectId()
    val entityId = UUID.randomUUID().toString

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
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"))

      val workItem = createWorkItem

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Right(HttpResponse(OK, "", Map.empty))))
      when(preferencesChangedService.completeAndDelete(any))
        .thenReturn(Future(true))

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Right(Result(s"Completed & deleted workitem: ${workItem.id} successfully: HttpResponse status=200"))
      )
    }

    "should complete with retry Failure for 429 TOO_MANY_REQUESTS" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"))

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", TOO_MANY_REQUESTS, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))

      val workItem = createWorkItem
      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          s"publish to subscriber EpsHodsAdapter" +
            s" failed, with HTTP response: [Oops], will retry. Workitem updated true"
        )
      )
      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(Failed))
    }

    "should complete with PermanentFailure for 429 TOO_MANY_REQUESTS if retry limit is reached" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"))

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", TOO_MANY_REQUESTS, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))

      val workItem = createWorkItem.copy(failureCount = 11)

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          s"publish to subscriber EpsHodsAdapter" +
            s" failed 11 times, marking as permanently failed\nError: " +
            s"uk.gov.hmrc.http.UpstreamErrorResponse: Oops. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(PermanentlyFailed))
    }

    // unrecoverable for any other 4XX
    "should complete with PermanentFailure for 400 BAD_REQUEST" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"))

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", BAD_REQUEST, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))

      val workItem = createWorkItem

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          "publish to subscriber EpsHodsAdapter permanently failed returning uk.gov.hmrc.http.UpstreamErrorResponse: Oops. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(PermanentlyFailed))
    }

    // recoverable 5XX
    "should complete with retryable Failure for 500 INTERNAL_SERVER_ERROR" in {
      val svc = new PublishSubscribersPublisher(preferencesChangedService, subscribers)
      val req = NotifySubscriberRequest(Digital, Instant.now(), Map("nino" -> "AB112233A"))

      when(epsConnector.notifySubscriber(any)(any, any))
        .thenReturn(Future(Left(UpstreamErrorResponse("Oops", INTERNAL_SERVER_ERROR, 0, Map.empty))))
      when(preferencesChangedService.completeWithStatus(any, any))
        .thenReturn(Future(true))

      val workItem = createWorkItem

      val response = svc.execute(req, workItem).futureValue
      response must equal(
        Left(
          "publish to subscriber EpsHodsAdapter failed, with HTTP response: [Oops], will retry. Workitem updated true"
        )
      )

      verify(preferencesChangedService, times(1)).completeWithStatus(any, ArgumentMatchers.eq(Failed))
    }

  }
}
