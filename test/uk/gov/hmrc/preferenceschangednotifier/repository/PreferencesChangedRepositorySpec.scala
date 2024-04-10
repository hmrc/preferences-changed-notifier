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
import org.mongodb.scala.model
import org.mongodb.scala.model.Filters
import org.scalatest.Suite
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{ be, convertToAnyMustWrapper }
import play.api.Configuration
import play.api.test.Helpers
import uk.gov.hmrc.mongo.test.{ DefaultPlayMongoRepositorySupport, MongoSupport }
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.{ Digital, Paper }
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChanged

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

class PreferencesChangedRepositorySpec
    extends AnyFreeSpec with MongoSupport with DefaultPlayMongoRepositorySupport[PreferencesChanged] with ScalaFutures
    with IntegrationPatience { this: Suite =>

  implicit val executionContext: ExecutionContext =
    Helpers.stubControllerComponents().executionContext

  val config: Configuration = Configuration(data = ("preferencesChanged.ttl", 14))

  val repository = new PreferencesChangedRepository(mongoComponent, config)

  override protected def checkTtlIndex: Boolean = true

  override protected def beforeEach(): Unit = {
    repository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    dropCollection()
    dropDatabase()
    super.beforeEach()
  }

  "Preferences changed repository" - {

    "test indexes" in {
      val indexes: Seq[model.IndexModel] = repository.indexes

      val maybeEntityIdIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("entityId") != null)

      maybeEntityIdIndexModel.isDefined must be(true)

      val maybeUpdatedAtIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("updatedAt") != null)

      maybeUpdatedAtIndexModel.get.getOptions
        .getExpireAfter(TimeUnit.DAYS) must be(14)
    }

    "inserts correctly" in {
      val entityId = UUID.randomUUID().toString
      val a = PreferencesChanged(
        _id = new ObjectId(),
        entityId,
        Paper,
        new ObjectId(),
        Instant.now(),
        Map("nino" -> "AB112233D")
      )
      val result = repository.upsert(a).futureValue
      result._id mustNot be(null)
    }

    "upserts by preferenceId correctly" in {
      val preferenceId = new ObjectId()
      val objectId = new ObjectId()
      val entityId = UUID.randomUUID().toString

      val a =
        PreferencesChanged(_id = objectId, entityId, Paper, preferenceId, Instant.now(), Map("nino" -> "AB112233D"))
      val r1 = repository.collection.insertOne(a).toFuture().futureValue
      r1.wasAcknowledged() mustEqual true

      val b = PreferencesChanged(
        _id = new ObjectId(),
        entityId,
        Digital,
        preferenceId,
        Instant.now(),
        Map("nino" -> "AB112233D")
      )
      val r2 = repository.upsert(item = b).futureValue
      r2._id must be(objectId)

      val item = repository.collection
        .find(
          filter = Filters.eq("entityId", entityId)
        )
        .toSingle()
        .toFuture()
        .futureValue
      item.changedValue mustEqual Digital
      item._id must be(objectId)
    }

    "inserts two separate new objects correctly" in {
      val preferenceId1 = new ObjectId()
      val entityId1 = UUID.randomUUID().toString
      val preferenceId2 = new ObjectId()
      val entityId2 = UUID.randomUUID().toString

      val objectId1 = new ObjectId()
      val objectId2 = new ObjectId()

      val a =
        PreferencesChanged(_id = objectId1, entityId1, Paper, preferenceId1, Instant.now(), Map("nino" -> "AB112233D"))

      // Insert object A
      val r1 = repository.collection.insertOne(a).toFuture().futureValue
      r1.wasAcknowledged() mustEqual true

      val b = PreferencesChanged(
        _id = objectId2,
        entityId2,
        Digital,
        preferenceId2,
        Instant.now(),
        Map("nino" -> "AB112233A")
      )

      val r2 = repository.upsert(item = b).futureValue
      r2._id must be(objectId2)

      val item = repository.collection
        .find(
          filter = Filters.eq("entityId", entityId1)
        )
        .toSingle()
        .toFuture()
        .futureValue
      item.changedValue mustEqual Paper
      item.entityId must be(entityId1)
    }
  }

}
