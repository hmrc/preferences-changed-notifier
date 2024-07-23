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

import com.github.tomakehurst.wiremock.client.WireMock.{ aResponse, post, urlEqualTo }
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.SingleObservableFuture
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{ BeforeAndAfterEach, EitherValues, TestSuite }
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import play.api.http.Status
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
import uk.gov.hmrc.preferenceschangednotifier.connectors.{ EpsHodsAdapterConnector, Subscriber, UpdatedPrintSuppressionsConnector }
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.{ PreferencesChanged, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.repository.{ PreferencesChangedRepository, PreferencesChangedWorkItemRepository }

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class PublishSubscribersServiceISpec
    extends AnyFreeSpec with Matchers with ScalaFutures with MongoSupport with BeforeAndAfterEach with EitherValues
    with TestSuite with IntegrationPatience with WireMockUtil {
  spec =>

  "publish subscribers service" - {

    "should process the workitem for both subscribers" in new TestSetup {
      val wi: (WorkItem[_], WorkItem[_]) = setupData()

      val countItems = pcwiRepo.collection.countDocuments().toFuture().futureValue
      countItems must be(2)

      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.OK))
          .build()
      )
      wireMockServer.addStubMapping(
        post(urlEqualTo("/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.OK))
          .build()
      )

      val result = service.execute.futureValue

      result.message must include(s"Completed & deleted workitem: ${wi._1.id} successfully: HttpResponse status=200")
      result.message must include(s"Completed & deleted workitem: ${wi._2.id} successfully: HttpResponse status=200")

      val postExecuteCount =
        pcwiRepo.collection.countDocuments().toFuture().futureValue
      postExecuteCount must be(0)
    }

    "should receive 400 for missing SaUtr" in new TestSetup {
      val wi: (WorkItem[_], WorkItem[_]) = setupData(addSaUtr = false)

      val countItems =
        pcwiRepo.collection.countDocuments().toFuture().futureValue

      countItems must be(2)

      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.OK)) // .withBody("An internal_server_error message"))
          .build()
      )
      wireMockServer.addStubMapping(
        post(urlEqualTo("/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.BAD_REQUEST).withBody("Missing SaUtr"))
          .build()
      )

      val result = service.execute.futureValue

      result.message must include(s"Completed & deleted workitem: ${wi._1.id} successfully: HttpResponse status=200")
      result.message must include(s"permanently failed")
      result.message must include(s"Missing SaUtr")

      // There will be one item left which did not get processed successfully
      val postExecuteCount = pcwiRepo.collection.countDocuments().toFuture().futureValue
      postExecuteCount must be(1)
    }

    "return correctly when eps-hods-adapter returns 4XX error" in new TestSetup {
      setupData()
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.BAD_REQUEST).withBody("A bad_request message"))
          .build()
      )
      wireMockServer.addStubMapping(
        post(urlEqualTo("/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.BAD_REQUEST).withBody("A bad_request message"))
          .build()
      )
      private val result = service.execute.futureValue
      result.message must include("permanently failed")
      result.message must include("'A bad_request message'")
    }

    "return Failed (which is retried) when stub returns 5XX error" in new TestSetup {
      setupData()
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR).withBody("An internal_server_error message"))
          .build()
      )
      wireMockServer.addStubMapping(
        post(urlEqualTo("/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR).withBody("An internal_server_error message"))
          .build()
      )

      private val result = service.execute.futureValue

      result.message must include("returned 500")
      result.message must include("An internal_server_error message")
    }

    "return Failed (which is retried) when returns a non 200 status" in new TestSetup {
      val wi = setupData()
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.CREATED).withBody("An internal_server_error message"))
          .build()
      )
      wireMockServer.addStubMapping(
        post(urlEqualTo("/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(Status.CREATED).withBody("An internal_server_error message"))
          .build()
      )

      private val result = service.execute.futureValue

      result.message must include(
        s"Completed & deleted workitem: ${wi._1.id} successfully: HttpResponse status=${Status.CREATED}"
      )
    }
  }

  trait TestSetup {
    val app: Application =
      GuiceApplicationBuilder()
        .configure(
          "metrics.enabled"                                       -> false,
          "preferencesChanged.retryFailedAfter"                   -> 10,
          "microservice.services.eps-hods-adapter.host"           -> "localhost",
          "microservice.services.eps-hods-adapter.port"           -> wireMockServer.port(),
          "scheduling.PublishSubscribersJob.taskEnabled"          -> false,
          "microservice.services.updated-print-suppressions.host" -> "localhost",
          "microservice.services.updated-print-suppressions.port" -> wireMockServer.port()
        )
        .build()

    val EpsSubscriber: String = "EpsHodsAdapter"
    val UpsSubscriber: String = "UpdatedPrintSuppressions"

    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val service = app.injector.instanceOf[PublishSubscribersService]
    val epsConnector = app.injector.instanceOf[EpsHodsAdapterConnector]
    val upsConnector = app.injector.instanceOf[UpdatedPrintSuppressionsConnector]
    val pcRepo = app.injector.instanceOf[PreferencesChangedRepository]
    val pcwiRepo = app.injector.instanceOf[PreferencesChangedWorkItemRepository]

    pcRepo.collection.drop().toFuture().futureValue
    pcwiRepo.collection.drop().toFuture().futureValue

    val subscribers: Seq[Subscriber] = Seq(epsConnector, upsConnector)

    // push an item into the pc repo
    val prefId = new ObjectId()
    val entityId = UUID.randomUUID().toString

    val pc = createPc(
      prefId = prefId,
      entityId = entityId,
      taxIds = Map("nino" -> "AB112233C", "sautr" -> "sautr1")
    )

    def setupData(addNino: Boolean = true, addSaUtr: Boolean = true): (WorkItem[_], WorkItem[_]) = {
      val ninoPair = if (addNino) Seq("nino" -> "AB112233C") else Nil
      val sautrPair = if (addSaUtr) Seq("sautr" -> "sautr1") else Nil
      val m = (ninoPair ++ sautrPair).toMap

      val prefId = new ObjectId()
      val entityId = UUID.randomUUID().toString

      val pc = createPc(
        prefId = prefId,
        entityId = entityId,
        taxIds = m
      )

      // insert a preference changed document
      val preferenceChangedRes = pcRepo.upsert(pc).futureValue

      val pcr1 = createPcr(preferenceChangedRes._id, prefId, entityId, EpsSubscriber)
      val pcr2 = createPcr(preferenceChangedRes._id, prefId, entityId, UpsSubscriber)

      // insert a workitem
      val wi1 = pcwiRepo.pushUpdated(pcr1).futureValue
      val wi2 = pcwiRepo.pushUpdated(pcr2).futureValue
      (wi1, wi2)
    }

    private def createPcr(preferenceChangedId: ObjectId, preferenceId: ObjectId, entityId: String, subscriber: String) =
      PreferencesChangedRef(
        preferenceChangedId = preferenceChangedId,
        preferenceId = preferenceId,
        entityId = entityId,
        subscriber = subscriber
      )

    private def createPc(prefId: ObjectId, entityId: String, taxIds: Map[String, String]) =
      PreferencesChanged(
        _id = new ObjectId,
        changedValue = Paper,
        preferenceId = prefId,
        entityId = entityId,
        updatedAt = Instant.now(),
        taxIds = taxIds // Map("nino" -> "YY000200A", "sautr" -> "SAUTR1")
      )
  }
}
