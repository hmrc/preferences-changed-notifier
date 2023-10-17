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

package uk.gov.hmrc.preferenceschangednotifier.model

import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.RestInstantFormat

import java.time.Instant

case class NotifySubscriberRequest(
    changedValue: MessageDeliveryFormat,
    updatedAt: Instant,
    taxIds: Map[String, String]
)

object NotifySubscriberRequest {
  def apply(pc: PreferencesChanged): NotifySubscriberRequest =
    NotifySubscriberRequest(pc.changedValue, pc.updatedAt, pc.taxIds)

  private implicit val dtf: Writes[Instant] =
    RestInstantFormat.writes
  private implicit val mdf: Writes[MessageDeliveryFormat] =
    MessageDeliveryFormat.writes

  implicit val writes: Writes[NotifySubscriberRequest] =
    Json.writes[NotifySubscriberRequest]
}
