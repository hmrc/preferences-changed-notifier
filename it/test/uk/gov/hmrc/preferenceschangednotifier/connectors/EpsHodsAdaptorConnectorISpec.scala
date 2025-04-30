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

package uk.gov.hmrc.preferenceschangednotifier.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{ aResponse, post, urlEqualTo }
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Application
import play.api.http.Status
import play.api.http.Status.{ BAD_REQUEST, NOT_FOUND, OK }
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.NotifySubscriberRequest

import java.time.Instant
import scala.concurrent.ExecutionContext

class EpsHodsAdaptorConnectorISpec
    extends PlaySpec with ScalaFutures with WireMockUtil with MockitoSugar with IntegrationPatience
    with BeforeAndAfterEach with EitherValues {

  "Connector..." must {
    "return ok when stub returns OK" in new TestSetup {
      val req = new NotifySubscriberRequest(Paper, Instant.now(), taxIds = Map("nino" -> "AB112233C"))

      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
          )
          .build()
      )

      private val result = connector.notifySubscriber(req).futureValue
      result.foreach(r => r.status must be(OK))
    }

    "return left bad-request" in new TestSetup {
      val req = new NotifySubscriberRequest(Paper, Instant.now(), taxIds = Map("nino" -> "AB112233C"))
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(
            aResponse()
              .withStatus(Status.BAD_REQUEST)
          )
          .build()
      )

      val result = connector.notifySubscriber(req).futureValue
      result.left.value.statusCode must be(BAD_REQUEST)
    }

    "return left not found for bad url" in new TestSetup {
      val req = new NotifySubscriberRequest(Paper, Instant.now(), taxIds = Map("nino" -> "AB112233C"))

      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriberssss"))
          .build()
      )

      val result = connector.notifySubscriber(req).futureValue
      result.left.value.statusCode must be(NOT_FOUND)
    }
  }

  trait TestSetup {
    val app: Application =
      GuiceApplicationBuilder()
        .configure(
          "metrics.enabled"                              -> false,
          "microservice.services.eps-hods-adapter.host"  -> "localhost",
          "microservice.services.eps-hods-adapter.port"  -> wireMockServer.port(),
          "scheduling.PublishSubscribersJob.taskEnabled" -> false
        )
        .build()

    val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
    val servicesConfig: ServicesConfig = app.injector.instanceOf[ServicesConfig]

    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val connector: EpsHodsAdapterConnector = new EpsHodsAdapterConnector(httpClient, servicesConfig)
  }
}
