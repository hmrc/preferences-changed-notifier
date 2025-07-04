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
import play.api.libs.json.{ Format, Json, OFormat, OWrites, Reads, Writes }
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats

case class PreferencesChangedRef(
  preferenceChangedId: ObjectId, // reference to preferenceChanged._id
  preferenceId: ObjectId,
  entityId: EntityId, // INDEX
  subscriber: String
)

object PreferencesChangedRef {
  given Format[ObjectId] = MongoFormats.objectIdFormat
  import EntityId.given_Format_EntityId
  given OFormat[PreferencesChangedRef] = Json.format[PreferencesChangedRef]
}
