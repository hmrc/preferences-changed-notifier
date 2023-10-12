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

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status.OK
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Writes
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.NotifySubscriberRequest

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class EpsHodsAdaptorConnectorSpec
    extends PlaySpec
    with ScalaFutures
    with GuiceOneAppPerSuite
    with MockitoSugar
    with IntegrationPatience
    with BeforeAndAfterEach {

//  override implicit lazy val app: Application = GuiceApplicationBuilder()
  override def fakeApplication(): Application =
    GuiceApplicationBuilder().configure("metrics.enabled" -> false).build()

  val mockClient: HttpClient = mock[HttpClient]

  private val servicesConfig = app.injector.instanceOf[ServicesConfig]
  private implicit val ec = app.injector.instanceOf[ExecutionContext]

  implicit val hc = HeaderCarrier()
  private val connector =
    new EpsHodsAdapterConnector(mockClient, servicesConfig)

  override def beforeEach(): Unit = {
    reset(mockClient)
  }

  "The connector" must {
    "send a preference update in NPS and receive status Succeded" in {

      val req = new NotifySubscriberRequest(Paper,
                                            Instant.now(),
                                            taxIds = Map("nino" -> "AB112233C"))

      when(
        mockClient.POST[NotifySubscriberRequest, HttpResponse](
          any[String],
          any[NotifySubscriberRequest],
          any[Seq[(String, String)]])(any[Writes[NotifySubscriberRequest]],
                                      any[HttpReads[HttpResponse]],
                                      any[HeaderCarrier],
                                      any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, "", Map.empty)))

      val result: HttpResponse = connector.notifySubscriber(req).futureValue

      result.status mustBe OK
    }
  }

}
