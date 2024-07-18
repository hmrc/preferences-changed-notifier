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

import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.preferenceschangednotifier.model.NotifySubscriberRequest
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2

import java.net.URL
import scala.concurrent.ExecutionContext
import java.util.UUID
import scala.concurrent.Future

trait Subscriber {
  val name: String

  protected val httpClient: HttpClientV2
  protected def url: URL

  def taxIdsValid(value: Map[String, String]): Boolean

  def notifySubscriber(
    request: NotifySubscriberRequest
  )(implicit hc: HeaderCarrier, ex: ExecutionContext): Future[Either[UpstreamErrorResponse, HttpResponse]] =
    httpClient
      .post(url)
      .withBody(Json.toJson(request))
      .setHeader("CorrelationId" -> UUID.randomUUID().toString)
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
}
