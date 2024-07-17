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

import play.api.Logging
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import javax.inject.{ Inject, Singleton }

@Singleton
class UpdatedPrintSuppressionsConnector @Inject() (http: HttpClientV2, servicesConfig: ServicesConfig)
    extends Subscriber with Logging {

  override val name = "UpdatedPrintSuppressions"

  override val httpClient: HttpClientV2 = http

  val UpsTaxId = "sautr"

  lazy val baseUrl: String =
    servicesConfig.baseUrl("updated-print-suppressions")

  override def url: URL = url"$baseUrl/preferences/notify-subscriber"

  override def taxIdsValid(taxIds: Map[String, String]): Boolean =
    taxIds.getOrElse(UpsTaxId, "") != ""
}
