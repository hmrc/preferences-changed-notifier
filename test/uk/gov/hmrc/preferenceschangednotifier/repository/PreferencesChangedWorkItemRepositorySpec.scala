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
import org.mongodb.scala.SingleObservableFuture
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{ be, must, mustBe, mustEqual }
import play.api.Configuration
import play.api.libs.json.Format
import play.api.test.Helpers
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.test.{ DefaultPlayMongoRepositorySupport, MongoSupport }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.preferenceschangednotifier.model.{ EntityId, PreferencesChangedRef }

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

class PreferencesChangedWorkItemRepositorySpec
    extends AnyFreeSpec with MongoSupport with DefaultPlayMongoRepositorySupport[WorkItem[PreferencesChangedRef]]
    with ScalaFutures with IntegrationPatience {
  spec =>

  implicit val executionContext: ExecutionContext =
    Helpers.stubControllerComponents().executionContext

  val config: Configuration = Configuration.from(
    Map("preferencesChangedWorkItems.retryInProgressAfter" -> 60000, "preferencesChangedWorkItems.ttl" -> 1)
  )

  val preferencesChangedWorkItemRepository =
    new PreferencesChangedWorkItemRepository(mongoComponent, config)

  override val repository: PlayMongoRepository[WorkItem[PreferencesChangedRef]] = preferencesChangedWorkItemRepository

  override protected def checkTtlIndex: Boolean = true

  override def beforeEach(): Unit = {
    preferencesChangedWorkItemRepository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    super.beforeEach()
  }

  "Preferences changed work item repository" - {
    val entityId = EntityId.generate()

    "test indexes" in {
      val indexes: Seq[model.IndexModel] = preferencesChangedWorkItemRepository.indexes

      val maybePreferenceIdIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("item.preferenceId") != null)

      maybePreferenceIdIndexModel.get.getOptions.isUnique must be(false)
      maybePreferenceIdIndexModel.get.getOptions.isBackground must be(true)

      val maybeSubscriberIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("item.subscriber") != null)

      maybeSubscriberIndexModel.get.getOptions.isUnique must be(false)
      maybeSubscriberIndexModel.get.getOptions.isBackground must be(true)

      val maybeUpdatedAtIndexModel =
        indexes.filter(i => i.getKeys.toBsonDocument.get("updatedAt") != null)

      maybeUpdatedAtIndexModel.find(_.getOptions.getExpireAfter(TimeUnit.DAYS) == 1).size must be(1)
    }

    "pushes new workitem correctly" in {
      val a = PreferencesChangedRef(new ObjectId(), new ObjectId(), entityId, "https://localhost:1234")
      val result = preferencesChangedWorkItemRepository.pushUpdated(a).futureValue
      result.id.toString.length mustEqual 24
    }

    "pull workitem" in {
      val s = "https://localhost:1234"
      val a = PreferencesChangedRef(new ObjectId(), new ObjectId(), entityId, s)
      preferencesChangedWorkItemRepository.pushNew(a).futureValue

      val workItem =
        preferencesChangedWorkItemRepository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get

      workItem.status mustEqual (ProcessingStatus.InProgress)
      workItem.item.subscriber mustEqual s
    }

    "complete workitem" in {
      val s = "https://localhost:1234"
      val a = PreferencesChangedRef(new ObjectId(), new ObjectId(), entityId, s)
      preferencesChangedWorkItemRepository.pushNew(a).futureValue

      val workItem =
        preferencesChangedWorkItemRepository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get
      val completed =
        preferencesChangedWorkItemRepository.complete(workItem.id, ProcessingStatus.Succeeded).futureValue

      completed mustEqual true
    }

    "modify existing workitem the preferenceId is already represented" in {
      val pcId = new ObjectId()
      val pId = new ObjectId()
      val a =
        PreferencesChangedRef(pcId, pId, entityId, "https://localhost:1234")

      preferencesChangedWorkItemRepository.pushNew(a).futureValue

      val workItem =
        preferencesChangedWorkItemRepository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get
      val completed =
        preferencesChangedWorkItemRepository.complete(workItem.id, ProcessingStatus.Succeeded).futureValue
      completed mustEqual true
    }

    "update item with same preferenceId and subscriber" in {
      val pcId = new ObjectId()
      val pId = new ObjectId()
      val a =
        PreferencesChangedRef(pcId, pId, entityId, "https://localhost:1234")

      val wi1 = preferencesChangedWorkItemRepository.pushUpdated(a).futureValue
      val wi2 = preferencesChangedWorkItemRepository.pushUpdated(a).futureValue

      wi1.updatedAt.isBefore(wi2.updatedAt) mustBe true
    }

    "create new item if same preferenceId and subscriber item is already in progress" in {
      val prefId = new ObjectId()
      val a =
        PreferencesChangedRef(new ObjectId(), prefId, entityId, "https://localhost:1234")
      val b =
        PreferencesChangedRef(new ObjectId(), prefId, entityId, "https://localhost:1234")

      val wi1 = preferencesChangedWorkItemRepository.pushUpdated(a).futureValue
      preferencesChangedWorkItemRepository.markAs(wi1.id, ProcessingStatus.InProgress).futureValue
      val wi2 = preferencesChangedWorkItemRepository.pushUpdated(b).futureValue // creates a new workitem

      wi1.receivedAt.isBefore(wi2.receivedAt) mustBe true
      preferencesChangedWorkItemRepository.collection.countDocuments().toFuture().futureValue mustBe 2
    }

    "push duplicate items in todo state" in {
      val prefId = new ObjectId()
      val prefChangedId = new ObjectId()

      val a =
        PreferencesChangedRef(prefChangedId, prefId, entityId, "https://localhost:1234")
      val b =
        PreferencesChangedRef(prefChangedId, prefId, entityId, "https://localhost:1234")

      val wi1 = preferencesChangedWorkItemRepository.pushUpdated(a).futureValue
      val wi2 = preferencesChangedWorkItemRepository.pushUpdated(b).futureValue // updates existing workitem

      wi1.item.preferenceId mustEqual (wi2.item.preferenceId)
      wi1.item.preferenceChangedId mustEqual (wi2.item.preferenceChangedId)
      wi1.item.subscriber mustEqual (wi2.item.subscriber)
      wi1.status mustEqual (wi2.status)
    }

  }

}
