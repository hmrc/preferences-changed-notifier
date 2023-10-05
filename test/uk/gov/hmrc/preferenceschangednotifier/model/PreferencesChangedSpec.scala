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

import org.scalatest.freespec.AnyFreeSpec
import org.mongodb.scala.bson.ObjectId
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import play.api.libs.json.JsObject
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper

import java.time.Instant

/**
  * Test that the case class formatting is correct for Mongo
  */
class PreferencesChangedSpec extends AnyFreeSpec {
  spec =>

  "Preferences changed json writes" - {

    "MessageDeliveryFormat format correctly" in {
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = new ObjectId(),
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )

      val writer = PreferencesChanged.writes
      val json = writer.writes(a)

      (json \ "changedValue").as[String] mustEqual (Paper.name)
    }

    "instant mongo format correctly" in {
      val now = Instant.now()
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = new ObjectId(),
        updatedAt = now,
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )

      val writer = PreferencesChanged.writes
      val json = writer.writes(a)

      val dateResult = (json \ "updatedAt").as[JsObject]
      val dateFormat = dateResult("$date").as[Map[String, String]]

      dateFormat mustEqual Map("$numberLong" -> now.toEpochMilli.toString)
    }

    "ObjectId format correctly" in {
      val preferenceId = new ObjectId()
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = preferenceId,
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )

      val writer = PreferencesChanged.writes
      val json = writer.writes(a)

      val result = (json \ "preferenceId" \ "$oid").as[String]
      result mustEqual preferenceId.toString
    }

    "taxIds format correctly" in {
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = new ObjectId(),
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D",
          "sautr" -> "sautr1"
        )
      )

      val writer = PreferencesChanged.writes
      val json = writer.writes(a)

      val taxIds = (json \ "taxIds").as[Map[String, String]]
      taxIds mustEqual Map("nino" -> "AB112233D", "sautr" -> "sautr1")
    }
  }
}
