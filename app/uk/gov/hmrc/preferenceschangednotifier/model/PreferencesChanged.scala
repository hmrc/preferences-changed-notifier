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

import org.mongodb.scala.bson.ObjectId
import play.api.libs.json.{ Format, Json, OFormat, OWrites, Reads }
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest

import java.time.Instant

/** PreferencesChanged encapsulates values we want to store in the mongo repository of the same name. Must ensure that
  * instant formatting is consistent with mongo.
  */
case class PreferencesChanged(
  _id: ObjectId,
  entityId: EntityId,
  changedValue: MessageDeliveryFormat,
  preferenceId: ObjectId,
  updatedAt: Instant,
  taxIds: Map[String, String],
  bounced: Boolean
)

object PreferencesChanged {

  // Create a mongo type from the rest request
  def from(pcRequest: PreferencesChangedRequest): PreferencesChanged =
    PreferencesChanged(
      _id = new ObjectId(),
      entityId = EntityId(pcRequest.entityId),
      changedValue = pcRequest.changedValue,
      preferenceId = new ObjectId(pcRequest.preferenceId),
      updatedAt = pcRequest.updatedAt,
      taxIds = pcRequest.taxIds,
      bounced = pcRequest.bounced
    )

  given Format[ObjectId] = MongoFormats.objectIdFormat
  given Format[Instant] = MongoJavatimeFormats.instantFormat
  import EntityId.given_Format_EntityId
  import MessageDeliveryFormat.given_Format_MessageDeliveryFormat

  given OFormat[PreferencesChanged] = Json.format[PreferencesChanged]
}
