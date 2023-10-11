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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{convertToAnyMustWrapper, equal}
import play.api.libs.json.Json
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper

import java.time.Instant

class PreferencesChangedRequestSpec extends AnyFreeSpec {
  spec =>

  "Preferences changed request json reads" - {

    "MessageDeliveryFormat correctly" in {
      val pcr: PreferencesChangedRequest = createPcr
      pcr.changedValue mustEqual Paper
    }

    "instant correctly" in {
      val pcr: PreferencesChangedRequest = createPcr
      pcr.updatedAt must equal(Instant.parse("2023-10-11T01:30:00.000Z"))
    }

    "preferenceId format correctly" in {
      val pcr: PreferencesChangedRequest = createPcr
      pcr.preferenceId mustEqual "65263df8d843592d74a2bfc6"
    }

    "taxIds format correctly" in {
      val pcr: PreferencesChangedRequest = createPcr
      pcr.taxIds mustEqual Map("nino" -> "AB112233C")
    }
  }

  private def createPcr = {
    val reader = PreferencesChangedRequest.reads
    reader.reads(createJson()).get
  }

  private def createJson(
      changedValue: String = "paper",
      preferenceId: String = "65263df8d843592d74a2bfc6",
      updatedAt: String = "2023-10-11T01:30:00.000Z",
      taxIds: String = """ { "nino" : "AB112233C" }"""
  ) = {
    Json.parse(s"""{
        |  "changedValue" : "$changedValue",
        |  "preferenceId" : "$preferenceId",
        |  "updatedAt"    : "$updatedAt",
        |  "taxIds"       :  $taxIds
        |}
        |""".stripMargin)
  }
}
