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

package uk.gov.hmrc.preferenceschangednotifier.repository

import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import play.api.Configuration
import play.api.test.Helpers
import uk.gov.hmrc.mongo.test.{DefaultPlayMongoRepositorySupport, MongoSupport}
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.{
  Digital,
  Paper
}
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChanged

import java.time.Instant
import scala.concurrent.ExecutionContext

class PreferencesChangedRepositorySpec
    extends AnyFreeSpec
    with MongoSupport
    with DefaultPlayMongoRepositorySupport[PreferencesChanged]
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterEach {
  spec =>

  implicit val executionContext: ExecutionContext =
    Helpers.stubControllerComponents().executionContext
  val config: Configuration = Configuration(
    data = ("preferencesChanged.ttl", 14))
  val repository = new PreferencesChangedRepository(mongoComponent, config)

  override protected def checkTtlIndex: Boolean = true

  override def beforeEach(): Unit = {
    repository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    super.beforeEach()
  }

  "Preferences changed repository" - {

    "must be empty" in {
      val count = repository.collection.countDocuments().toFuture().futureValue
      count mustEqual 0L
    }

    "inserts correctly" in {
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = new ObjectId(),
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )
      val result = repository.replace(a).futureValue
      result.wasAcknowledged mustEqual true
    }

    "upserts by preferenceId correctly" in {
      val preferenceId = new ObjectId()
      val a = PreferencesChanged(
        changedValue = Paper,
        preferenceId = preferenceId,
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )
      val r1 = repository.collection.insertOne(a).toFuture().futureValue
      r1.wasAcknowledged() mustEqual (true)

      val b = PreferencesChanged(
        changedValue = Digital,
        preferenceId = preferenceId,
        updatedAt = Instant.now(),
        taxIds = Map(
          "nino" -> "AB112233D"
        )
      )
      val r2 = repository.replace(item = b).futureValue
      r2.wasAcknowledged() mustEqual (true)

      val item = repository.collection
        .find(
          filter = Filters.eq("preferenceId", preferenceId)
        )
        .toSingle()
        .toFuture()
        .futureValue
      item.changedValue mustEqual (Digital)
    }
  }

}
