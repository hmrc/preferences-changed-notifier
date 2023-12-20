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

import org.mongodb.scala.model.Filters
import org.scalatest.{BeforeAndAfterEach, Suite, TestSuite}
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
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.preferenceschangednotifier.repository.{PreferencesChangedRepository, PreferencesChangedWorkItemRepository}

import java.util.UUID
import scala.concurrent.ExecutionContext

class PreferencesChangedControllerISpec
  extends AnyFreeSpec
    with Matchers
    with TestSuite
    with GuiceOneServerPerSuite
    with ScalaFutures
    with IntegrationPatience
    with MongoSupport
    with BeforeAndAfterEach
    with Injecting { this: Suite =>

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  override implicit lazy val app: Application = new GuiceApplicationBuilder()
    .configure("metrics.enabled" -> false)
    .build()
  
  private val controller = inject[PreferencesChangedController]
  private val repo       = inject[PreferencesChangedRepository]
  private val wiRepo     = inject[PreferencesChangedWorkItemRepository]

  override protected def beforeEach(): Unit = {
    repo.collection.deleteMany(Filters.empty()).toFuture().futureValue
    wiRepo.collection.deleteMany(Filters.empty()).toFuture().futureValue
    dropDatabase()
    super.beforeEach()
  }
  private def createFakePostRequest(reqBody: String, subscribeUps: Boolean) = {
    val headers: (String,String) = if (subscribeUps) {
      "X-SUBSCRIBE-UPS" -> "true"
    } else {
      "X-SUBSCRIBE-UPS" -> "false"
    }
    
    FakeRequest(
      "POST",
      routes.PreferencesChangedController.preferencesChanged().url,
      FakeHeaders(Seq(CONTENT_TYPE -> ContentTypes.JSON, headers)),
      Json.parse(reqBody)
    )
  }

  "POST /preferences-changed" - {
    val entityId = UUID.randomUUID().toString

    "return 200 for eps subscriber only" in {
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "65263df8d843592d74a2bfc6",
           |  "entityId"     : "$entityId",
           |  "updatedAt"    : "2023-10-11T01:30:00.000Z",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" }
           |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody, subscribeUps = false)
      val result = controller.preferencesChanged()(fakePostRequest).futureValue
      result.header.status must be(OK)
      
      val repoCount   =   repo.collection.countDocuments().toFuture().futureValue
      val wiRepoCount = wiRepo.collection.countDocuments().toFuture().futureValue
      
      repoCount must be(1)
      wiRepoCount must be(1)
    }

    "return 200 for both subscribers" in {
      val reqBody =
        s"""{
           |  "changedValue" : "paper",
           |  "preferenceId" : "65263df8d843592d74a2bfc6",
           |  "entityId"     : "$entityId",
           |  "updatedAt"    : "2023-10-11T01:30:00.000Z",
           |  "taxIds"       : { "nino" : "AB112233C", "sautr" : "abcde" }
           |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody, subscribeUps = true)
      val result = controller.preferencesChanged()(fakePostRequest).futureValue
      result.header.status must be(OK)
      
      val repoCount   =   repo.collection.countDocuments().toFuture().futureValue
      val wiRepoCount = wiRepo.collection.countDocuments().toFuture().futureValue
      
      repoCount must be(1)
      wiRepoCount must be(2)
    }

    "return 400 when the date is incorrectly formatted" in {
      val reqBody =
        s"""{
          |"changedValue" : "paper",
          |"preferenceId" : "65263df8d843592d74a2bfc6",
          |"entityId"     : "$entityId",
          |"updatedAt"    : "023-10-11T01:30:00.000Z",
          |"taxIds"       : {"nino":"AB112233C"}
          |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody, subscribeUps = true)
      val result = controller.preferencesChanged()(fakePostRequest)
      
      status(result) must be(BAD_REQUEST)
      
      val errStr = "Invalid PreferencesChangedRequest payload: List" +
        "((/updatedAt,List(JsonValidationError(List(Could not parse " +
        "023-10-11T01:30:00.000Z as an ISO Instant),ArraySeq()))))"

      contentAsString(result) must be(errStr)
    }
    

    "return 400 when the objectid is incorrectly formatted" in {
      val reqBody =
        s"""{
          |  "changedValue" : "paper",
          |  "preferenceId" : "5555",
          |  "entityId"     : "$entityId",
          |  "updatedAt"    : "2023-10-11T01:30:00.000Z",
          |  "taxIds"       : {"nino":"AB112233C"}
          |}""".stripMargin

      val fakePostRequest = createFakePostRequest(reqBody, subscribeUps = true)
      val result = controller.preferencesChanged()(fakePostRequest)

      status(result) must be(BAD_REQUEST)

      val errStr = "java.lang.IllegalArgumentException: invalid hexadecimal representation of an ObjectId: [5555]"

      contentAsString(result) must be(errStr)
    }
    
  }
}
