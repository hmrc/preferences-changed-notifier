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

import org.scalatest.TestSuite
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.ContentTypes
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.{CONTENT_TYPE, contentAsString, defaultAwaitTimeout, status}
import play.api.test.{FakeHeaders, FakeRequest, Injecting}

import scala.concurrent.ExecutionContext

class PreferencesChangedControllerISpec
  extends AnyFreeSpec
    with Matchers
    with TestSuite
    with GuiceOneServerPerSuite
    with ScalaFutures
    with IntegrationPatience
    with Injecting {

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  override implicit lazy val app: Application = new GuiceApplicationBuilder()
    .configure("metrics.enabled" -> false)
    .build()
  
  private val controller = inject[PreferencesChangedController]

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
           |  "preferenceId" : "65263df8d843592d74a2bfc6",
           |  "updatedAt"    : "2023-10-11T01:30:00.000Z",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" }
           |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody)
      val result = controller.preferencesChanged()(fakePostRequest)
      
      status(result) must be(OK)
    }

    "return 400 when the date is incorrectly formatted" in {
      val reqBody =
        """{
          |"changedValue" : "paper",
          |"preferenceId" : "65263df8d843592d74a2bfc6",
          |"updatedAt"    : "023-10-11T01:30:00.000Z",
          |"taxIds"       : {"nino":"AB112233C"}
          |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody)
      val result = controller.preferencesChanged()(fakePostRequest)
      
      status(result) must be(BAD_REQUEST)
      
      val errStr = "Invalid PreferencesChangedRequest payload: List" +
        "((/updatedAt,List(JsonValidationError(List(Could not parse " +
        "023-10-11T01:30:00.000Z as an ISO Instant),ArraySeq()))))"

      contentAsString(result) must be(errStr)
    }
    

    "return 400 when the objectid is incorrectly formatted" in {
      val reqBody =
        """{
          |  "changedValue" : "paper",
          |  "preferenceId" : "5555",
          |  "updatedAt"    : "2023-10-11T01:30:00.000Z",
          |  "taxIds"       : {"nino":"AB112233C"}
          |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody)
      val result = controller.preferencesChanged()(fakePostRequest)

      status(result) must be(BAD_REQUEST)

      val errStr = "java.lang.IllegalArgumentException: invalid hexadecimal representation of an ObjectId: [5555]"

      contentAsString(result) must be(errStr)
    }
    
  }
}
