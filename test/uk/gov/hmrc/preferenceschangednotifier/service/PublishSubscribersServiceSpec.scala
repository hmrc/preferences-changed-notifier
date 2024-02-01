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

import com.github.tomakehurst.wiremock.client.WireMock.{
  aResponse,
  givenThat,
  post,
  urlEqualTo
}
import org.mongodb.scala.bson.ObjectId
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status.{BAD_REQUEST, CREATED, INTERNAL_SERVER_ERROR, OK}
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.{
  PreferencesChanged,
  PreferencesChangedRef
}
import uk.gov.hmrc.preferenceschangednotifier.repository.{
  PreferencesChangedRepository,
  PreferencesChangedWorkItemRepository
}

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class PublishSubscribersServiceSpec
    extends PlaySpec
    with ScalaFutures
    with GuiceOneAppPerSuite
    with IntegrationPatience
    with BeforeAndAfterEach
    with EitherValues
    with WireMockUtil
    with MongoSupport {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "metrics.enabled" -> false,
        "microservice.services.eps-hods-adapter.host" -> "localhost",
        "microservice.services.eps-hods-adapter.port" -> "22222",
        "microservice.services.updated-print-suppressions.host" -> "localhost",
        "microservice.services.updated-print-suppressions.port" -> "33333",
        "featureFlag.useUPS" -> false
      )
      .build()

  implicit val executionContext = ExecutionContext.Implicits.global

  private val service = app.injector.instanceOf[PublishSubscribersService]
  private val pcRepo = app.injector.instanceOf[PreferencesChangedRepository]
  private val pcwiRepo =
    app.injector.instanceOf[PreferencesChangedWorkItemRepository]

  override def beforeEach(): Unit = {
    pcRepo.collection.drop().toFuture().futureValue
    pcwiRepo.collection.drop().toFuture().futureValue
    super.beforeEach()
  }

  "Service..." must {

    "return correctly when stub returns 200 OK" in new TestCase {
      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(OK)))

      val result = service.execute.futureValue

      result.message must include(
        s"Completed & deleted workitem: ${wi1.id} successfully: HttpResponse status=200")
    }

    "return correctly when stub returns 4XX error" in new TestCase {
      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
              .withBody("A bad_request message")))

      private val result = service.execute.futureValue
      result.message must include("permanently failed")
      result.message must include("'A bad_request message'")
    }

    "return Failed (which is retried) when stub returns 5XX error" in new TestCase {
      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
              .withBody("An internal_server_error message")))

      private val result = service.execute.futureValue

      result.message must include("returned 500")
      result.message must include("An internal_server_error message")
    }

    "return Failed (which is retried) when returns a non 200 status" in new TestCase {
      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(
            aResponse()
              .withStatus(CREATED)
              .withBody("An internal_server_error message")))

      private val result = service.execute.futureValue

      result.message must include(
        s"Completed & deleted workitem: ${wi1.id} successfully: HttpResponse status=${CREATED}")
    }
  }

  private def createPcr(preferenceChangedId: ObjectId,
                        entityId: String,
                        preferenceId: ObjectId,
                        subscriber: String) = {
    PreferencesChangedRef(preferenceChangedId = preferenceChangedId,
                          preferenceId = preferenceId,
                          entityId = entityId,
                          subscriber = subscriber)
  }

  class TestCase {
    // push an item into the pc repo
    val prefId = new ObjectId()
    val entityId = UUID.randomUUID().toString

    val pc = PreferencesChanged(
      _id = new ObjectId(),
      changedValue = Paper,
      preferenceId = prefId,
      entityId = entityId,
      updatedAt = Instant.now(),
      taxIds = Map("nino" -> "AB112233C", "sautr" -> "sautr1"))

    // insert a preference changed document
    val preferenceChangedRes =
      pcRepo.upsert(pc).futureValue

    val pcr1 =
      createPcr(preferenceChangedRes._id, entityId, prefId, "EpsHodsAdapter")
    val pcr2 = createPcr(preferenceChangedRes._id,
                         entityId,
                         prefId,
                         "UpdatedPrintSuppressionAdapter")

    // insert a workitem
    val wi1 = pcwiRepo.pushUpdated(pcr1).futureValue
    val wi2 = pcwiRepo.pushUpdated(pcr2).futureValue
  }
}
