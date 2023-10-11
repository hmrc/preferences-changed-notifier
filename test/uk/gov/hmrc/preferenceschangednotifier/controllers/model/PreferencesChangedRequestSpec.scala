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
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{convertToAnyMustWrapper, equal}
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper

import java.time.Instant

class PreferencesChangedRequestSpec extends AnyFreeSpec {
  spec =>

  "Preferences changed request json writes" - {

    "MessageDeliveryFormat format correctly" in {
      val a = PreferencesChangedRequest(Paper,
                                        new ObjectId().toString,
                                        Instant.now(),
                                        taxIds = Map("nino" -> "AB112233D"))

      val writer = PreferencesChangedRequest.format
      val json = writer.writes(a)

      (json \ "changedValue").as[String] mustEqual (Paper.name)
    }

    "instant format correctly" in {
      val dateStr = "2023-10-12T09:30:00.000Z"
      val now = Instant.parse(dateStr)
      val a = PreferencesChangedRequest(Paper,
                                        new ObjectId().toString,
                                        now,
                                        taxIds = Map("nino" -> "AB112233D"))

      val writer = PreferencesChangedRequest.format
      val json = writer.writes(a)

      val dateResult = (json \ "updatedAt").as[String]

      dateResult must equal(dateStr)
    }

    "preferenceId format correctly" in {
      val preferenceId = new ObjectId().toString
      val a = PreferencesChangedRequest(
        changedValue = Paper,
        preferenceId = preferenceId,
        updatedAt = Instant.now(),
        taxIds = Map("nino" -> "AB112233D")
      )

      val writer = PreferencesChangedRequest.format
      val json = writer.writes(a)

      val result = (json \ "preferenceId").as[String]
      result mustEqual preferenceId.toString
    }

    "taxIds format correctly" in {
      val a = PreferencesChangedRequest(
        changedValue = Paper,
        preferenceId = new ObjectId().toString,
        updatedAt = Instant.now(),
        taxIds = Map("nino" -> "AB112233D", "sautr" -> "sautr1")
      )

      val writer = PreferencesChangedRequest.format
      val json = writer.writes(a)

      val taxIds = (json \ "taxIds").as[Map[String, String]]
      taxIds mustEqual Map("nino" -> "AB112233D", "sautr" -> "sautr1")
    }
  }
}
