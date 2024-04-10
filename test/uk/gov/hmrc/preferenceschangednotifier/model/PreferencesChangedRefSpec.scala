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
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import play.api.libs.json.Json

import java.util.UUID

class PreferencesChangedRefSpec extends AnyFreeSpec {
  spec =>

  "Preferences changed ref json writes" - {

    "preferencesChangedId ObjectId writes correctly" in {
      val preferenceChangedId = new ObjectId()
      val entityId = UUID.randomUUID().toString
      val a = PreferencesChangedRef(preferenceChangedId, new ObjectId(), entityId, "sub1")

      val writer = PreferencesChangedRef.writes
      val json = writer.writes(a)

      val result = (json \ "preferenceChangedId" \ "$oid").as[String]
      result mustEqual preferenceChangedId.toString
    }

    "preferencesId ObjectId writes correctly" in {
      val preferenceId = new ObjectId()
      val entityId = UUID.randomUUID().toString
      val a =
        PreferencesChangedRef(new ObjectId(), preferenceId, entityId, "sub1")

      val writer = PreferencesChangedRef.writes
      val json = writer.writes(a)

      val result = (json \ "preferenceId" \ "$oid").as[String]
      result mustEqual preferenceId.toString
    }

    "subscriber string writes correctly" in {
      val entityId = UUID.randomUUID().toString
      val a =
        PreferencesChangedRef(new ObjectId(), new ObjectId(), entityId, "sub1")

      val writer = PreferencesChangedRef.writes
      val json = writer.writes(a)

      val result = (json \ "subscriber").as[String]
      result mustEqual "sub1"
    }
  }

  "Preferences changed ref json reads" - {

    "preferencesChangedId ObjectId reads correctly" in {
      val preferenceChangedId = new ObjectId()
      val entityId = UUID.randomUUID().toString

      val strJson =
        s"""{
           |  "preferenceChangedId" : { "$$oid" : "${preferenceChangedId.toString}" },
           |  "preferenceId"        : { "$$oid" :  "${new ObjectId()}" },
           |  "entityId"            : "$entityId",
           |  "subscriber"          : "sub1"
           |}""".stripMargin

      val pcr = Json.parse(strJson).as[PreferencesChangedRef]

      pcr.preferenceChangedId mustEqual preferenceChangedId
    }

    "preferencesId ObjectId reads correctly" in {
      val preferenceId = new ObjectId()
      val entityId = UUID.randomUUID().toString

      val strJson =
        s"""{
           |  "preferenceChangedId" : { "$$oid" : "${new ObjectId()}" },
           |  "preferenceId"        : { "$$oid" : "${preferenceId.toString}" },
           |  "entityId"            : "$entityId",
           |  "subscriber"          : "sub1"
           |}""".stripMargin

      val pcr = Json.parse(strJson).as[PreferencesChangedRef]

      pcr.preferenceId mustEqual preferenceId
    }

    "subscriber string reads correctly" in {
      val entityId = UUID.randomUUID().toString

      val strJson =
        s"""{
           |  "preferenceChangedId" : { "$$oid" : "${new ObjectId()}" },
           |  "preferenceId"        : { "$$oid" : "${new ObjectId()}" },
           |  "entityId"            : "$entityId",
           |  "subscriber"          : "sub1"
           |}""".stripMargin

      val pcr = Json.parse(strJson).as[PreferencesChangedRef]

      pcr.subscriber mustEqual "sub1"
    }
  }

}
