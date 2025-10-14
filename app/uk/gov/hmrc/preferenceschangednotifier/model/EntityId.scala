/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.libs.json.*

import java.util.UUID

case class EntityId(value: String) {
  override def toString: String = value
}

object EntityId {
  def generate(): EntityId = EntityId(UUID.randomUUID().toString)

  val reads: Reads[EntityId] = new Reads[EntityId] {
    override def reads(json: JsValue): JsResult[EntityId] = json match {
      case JsString(s) => JsSuccess(EntityId(s))
      case _           => JsError("No entityId")
    }
  }

  val writes: Writes[EntityId] = new Writes[EntityId] {
    override def writes(e: EntityId): JsValue = JsString(e.value)
  }

  given Format[EntityId] = Format(reads, writes)
}
