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

import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.preferenceschangednotifier.model.{
  NotifySubscriberRequest,
  Subscriber
}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class EpsHodsAdapterConnector @Inject()(http: HttpClient,
                                        servicesConfig: ServicesConfig)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier)
    extends Subscriber
    with Logging {

  lazy val baseUrl: String = servicesConfig.baseUrl("eps-hods-adapter")

  private def postUrl = s"$baseUrl/eps-hods-adapter/notify-subscriber"

  override def notifySubscriber(
      request: NotifySubscriberRequest): Future[HttpResponse] = {

    logger.debug(s"notify-subscriber")

    http
      .POST[NotifySubscriberRequest, HttpResponse](
        postUrl,
        request,
        Seq("CorrelationId" -> UUID.randomUUID().toString)
      )
  }
}
