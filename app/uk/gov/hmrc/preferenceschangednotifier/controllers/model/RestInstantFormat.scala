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

import play.api.libs.json.{JsSuccess, JsError, JsString, Reads}

import java.time.Instant
import scala.util.Try

object RestInstantFormat {
  implicit val reads: Reads[Instant] = restInstantReads

  private lazy val restInstantReads: Reads[Instant] = {
    case JsString(s) =>
      Try(Instant.parse(s))
        .fold(
          _ => JsError(s"Could not parse $s as an ISO Instant"),
          JsSuccess.apply(_)
        )
    case json =>
      JsError(s"Expected value to be a string, was actually $json")
  }
}
