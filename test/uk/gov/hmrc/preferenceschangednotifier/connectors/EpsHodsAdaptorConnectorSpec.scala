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

import com.github.tomakehurst.wiremock.client.WireMock.{
  aResponse,
  givenThat,
  post,
  urlEqualTo
}
import org.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND, OK}
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.NotifySubscriberRequest

import java.time.Instant
import scala.concurrent.ExecutionContext

class EpsHodsAdaptorConnectorSpec
    extends PlaySpec
    with ScalaFutures
    with GuiceOneAppPerSuite
    with MockitoSugar
    with IntegrationPatience
    with BeforeAndAfterEach
    with EitherValues
    with WireMockUtil {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "metrics.enabled" -> false,
        "microservice.services.eps-hods-adapter.host" -> "localhost",
        "microservice.services.eps-hods-adapter.port" -> "22222"
      )
      .build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  private val servicesConfig = app.injector.instanceOf[ServicesConfig]

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val connector =
    new EpsHodsAdapterConnector(httpClient, servicesConfig)

  "Connector..." must {
    "return ok when stub returns OK" in {
      val req = new NotifySubscriberRequest(Paper,
                                            Instant.now(),
                                            taxIds = Map("nino" -> "AB112233C"))

      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse()
            .withStatus(Status.OK)))

      val result = connector.notifySubscriber(req).futureValue
      result.foreach(r => r.status must be(OK))
    }

    "return left bad-request" in {
      val req = new NotifySubscriberRequest(Paper,
                                            Instant.now(),
                                            taxIds = Map("nino" -> "AB112233C"))

      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse()
            .withStatus(Status.BAD_REQUEST)))

      val result = connector.notifySubscriber(req).futureValue
      result.left.value.statusCode must be(BAD_REQUEST)
    }

    "return left not found for bad url" in {
      val req = new NotifySubscriberRequest(Paper,
                                            Instant.now(),
                                            taxIds = Map("nino" -> "AB112233C"))

      givenThat(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriberssss")))

      val result = connector.notifySubscriber(req).futureValue
      result.left.value.statusCode must be(NOT_FOUND)
    }
  }

}
