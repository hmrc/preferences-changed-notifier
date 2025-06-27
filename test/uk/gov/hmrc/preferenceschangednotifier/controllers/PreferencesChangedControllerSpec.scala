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

package uk.gov.hmrc.preferenceschangednotifier.controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.data.EitherT.rightT
import cats.implicits.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mongodb.scala.bson.ObjectId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.must
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.{ ContentTypes, Status }
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{ FakeHeaders, FakeRequest, Helpers }
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.service.PreferencesChangedService
import uk.gov.hmrc.preferenceschangednotifier.model.{ ErrorResponse, ServerError }

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.global

class PreferencesChangedControllerSpec extends AnyFreeSpec with Matchers {

  implicit lazy val system: ActorSystem = ActorSystem()
  implicit lazy val materializer: Materializer = Materializer(system)
  implicit def ec: ExecutionContext = global

  private val service = mock[PreferencesChangedService]
  private val controller = new PreferencesChangedController(Helpers.stubControllerComponents(), service)

  private def createFakePostRequest(reqBody: String) =
    FakeRequest(
      "POST",
      routes.PreferencesChangedController.preferencesChanged().url,
      FakeHeaders(Seq(CONTENT_TYPE -> ContentTypes.JSON)),
      Json.parse(reqBody)
    )

  "POST /preferences-changed" - {

    "return 200" in {
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "${new ObjectId().toString}",
           |  "entityId"     : "${UUID.randomUUID().toString}",
           |  "updatedAt"    : "${Instant.now()}",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" },
           |  "bounced"      : true
           |}""".stripMargin

      val fakePostRequest =
        createFakePostRequest(reqBody)

      when(service.preferenceChanged(any[PreferencesChangedRequest]))
        .thenReturn {
          EitherT.rightT[Future, Unit](())
        }

      val result = controller.preferencesChanged()(fakePostRequest)
      status(result) shouldBe Status.OK
    }

    "return 400 with a badly formatted date" in {
      val reqBody =
        s"""{
           |"changedValue": "paper",
           |"preferenceId": "5555",
           |"entityId"    : "${UUID.randomUUID().toString}",
           |"updatedAt"   : "023-10-11T01:30:00.000Z",
           |"taxIds"      : {"nino":"AB112233C"},
           |"bounced"     : true
           |}""".stripMargin

      val fakePostRequest =
        createFakePostRequest(reqBody)

      when(service.preferenceChanged(any[PreferencesChangedRequest]))
        .thenReturn(EitherT.leftT[Future, ErrorResponse](ServerError("whatever")))

      val result = controller.preferencesChanged()(fakePostRequest)

      contentAsString(result) contains "Could not parse 023-10-11T01:30:00.000Z as an ISO Instant"
      status(result) shouldBe Status.BAD_REQUEST
    }

    "return 500 when service returns an error" in {
      val pid = new ObjectId()
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "${pid.toString}",
           |  "entityId"     : "${UUID.randomUUID().toString}",
           |  "updatedAt"    : "2023-10-11T09:30:00.000Z",
           |  "taxIds"       : { "nino" : "AB112233C" },
           |  "bounced"      : true
           |}
           |""".stripMargin

      val fakePostRequest =
        createFakePostRequest(reqBody)

      when(service.preferenceChanged(any[PreferencesChangedRequest]))
        .thenReturn(EitherT.leftT[Future, String](ServerError("whatever")))

      val result = controller.preferencesChanged()(fakePostRequest)
      contentAsString(result) must equal("whatever")
      status(result) must be(Status.INTERNAL_SERVER_ERROR)
    }

    "still succeeds when specifying dont subscribe to UPS flag" in {
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "${new ObjectId().toString}",
           |  "entityId"     : "${UUID.randomUUID().toString}",
           |  "updatedAt"    : "${Instant.now()}",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" },
           |  "bounced"      : false
           |}""".stripMargin

      val fakePostRequest =
        createFakePostRequest(reqBody)

      when(service.preferenceChanged(any[PreferencesChangedRequest]))
        .thenReturn(EitherT.rightT[Future, Unit](()))

      val result = controller.preferencesChanged()(fakePostRequest)
      status(result) shouldBe Status.OK
    }

    "still succeeds when subscribe to UPS flag does not exist" in {
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "${new ObjectId().toString}",
           |  "entityId"     : "${UUID.randomUUID().toString}",
           |  "updatedAt"    : "${Instant.now()}",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" },
           |  "bounced"      : true
           |}""".stripMargin

      val fakePostRequest =
        createFakePostRequest(reqBody)

      when(service.preferenceChanged(any[PreferencesChangedRequest]))
        .thenReturn(EitherT.rightT[Future, Unit](()))

      val result = controller.preferencesChanged()(fakePostRequest)
      status(result) shouldBe Status.OK
    }

  }

}
