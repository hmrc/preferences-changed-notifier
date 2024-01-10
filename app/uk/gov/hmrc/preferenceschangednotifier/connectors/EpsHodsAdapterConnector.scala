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

import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.Singleton
@Singleton
class EpsHodsAdapterConnector @Inject()(http: HttpClient,
                                        servicesConfig: ServicesConfig)
    extends Subscriber
    with Logging {

  override val name = "EpsHodsAdapter"

  override val httpClient: HttpClient = http

  val NpsTaxId = "nino"

  lazy val baseUrl: String = servicesConfig.baseUrl("eps-hods-adapter")

  override def url: String =
    s"$baseUrl/eps-hods-adapter/preferences/notify-subscriber"

  override def taxIdsValid(taxIds: Map[String, String]): Boolean =
    taxIds.getOrElse(NpsTaxId, "") != ""
}
