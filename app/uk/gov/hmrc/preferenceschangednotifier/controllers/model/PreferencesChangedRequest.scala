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

package uk.gov.hmrc.preferenceschangednotifier.controllers.model

import org.mongodb.scala.bson.ObjectId
import play.api.libs.json.{ Format, Json, Reads }
import uk.gov.hmrc.preferenceschangednotifier.model.{ EntityId, MessageDeliveryFormat }

import java.time.Instant

case class PreferencesChangedRequest(
  changedValue: MessageDeliveryFormat,
  preferenceId: String,
  entityId: String,
  updatedAt: Instant,
  taxIds: Map[String, String],
  bounced: Boolean
)

object PreferencesChangedRequest {
  import MessageDeliveryFormat.given_Format_MessageDeliveryFormat
  import RestInstantFormat.reads
  given Reads[PreferencesChangedRequest] = Json.reads[PreferencesChangedRequest]
}
