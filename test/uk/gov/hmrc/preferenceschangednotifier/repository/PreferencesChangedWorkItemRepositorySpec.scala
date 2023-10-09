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
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.{be, convertToAnyMustWrapper}
import play.api.Configuration
import play.api.test.Helpers
import uk.gov.hmrc.mongo.test.{DefaultPlayMongoRepositorySupport, MongoSupport}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChangedRef

import java.time.Instant
import scala.concurrent.ExecutionContext

class PreferencesChangedWorkItemRepositorySpec
    extends AnyFreeSpec
    with MongoSupport
    with DefaultPlayMongoRepositorySupport[WorkItem[PreferencesChangedRef]]
    with ScalaFutures
    with IntegrationPatience {
  spec =>

  implicit val executionContext: ExecutionContext =
    Helpers.stubControllerComponents().executionContext

  val config: Configuration = Configuration(
    data = ("preferencesChangedWorkItems.retryInProgressAfter", 60000))

  override val repository =
    new PreferencesChangedWorkItemRepository(mongoComponent, config)

  override protected def checkTtlIndex: Boolean = false

  override def beforeEach(): Unit = {
    repository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    super.beforeEach()
  }

  "Preferences changed repository" - {
    "test indexes" in {
      val indexes: Seq[model.IndexModel] = repository.indexes

      val maybePreferenceIdIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("item.preferenceId") != null)
      
      maybePreferenceIdIndexModel.get.getOptions.isUnique must be(false)
      maybePreferenceIdIndexModel.get.getOptions.isBackground must be(true)
      
      val maybeSubscriberIndexModel =
        indexes.find(i => i.getKeys.toBsonDocument.get("item.subscriber") != null)

      maybeSubscriberIndexModel.get.getOptions.isUnique must be(false)
      maybeSubscriberIndexModel.get.getOptions.isBackground must be(true)

    }

    "pushes new workitem correctly" in {
      val a = PreferencesChangedRef(new ObjectId(),
                                    new ObjectId(),
                                    "https://localhost:1234")
      val result = repository.pushUpdated(a).futureValue
      result.id.toString.length mustEqual 24
    }

    "pull workitem" in {
      val s = "https://localhost:1234"
      val a = PreferencesChangedRef(new ObjectId(), new ObjectId(), s)
      repository.pushNew(a).futureValue

      val workItem =
        repository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get

      workItem.status mustEqual (ProcessingStatus.InProgress)
      workItem.item.subscriber mustEqual (s)
    }

    "complete workitem" in {
      val s = "https://localhost:1234"
      val a = PreferencesChangedRef(new ObjectId(), new ObjectId(), s)
      repository.pushNew(a).futureValue

      val workItem =
        repository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get
      val completed =
        repository.complete(workItem.id, ProcessingStatus.Succeeded).futureValue

      completed mustEqual true
    }

    "modify existing workitem the preferenceId is already represented" in {
      val pcId = new ObjectId()
      val pId = new ObjectId()
      val a = PreferencesChangedRef(pcId, pId, "https://localhost:1234")

      repository.pushNew(a).futureValue

      val workItem =
        repository.pullOutstanding(Instant.now(), Instant.now()).futureValue.get
      val completed =
        repository.complete(workItem.id, ProcessingStatus.Succeeded).futureValue
      completed mustEqual true
    }

    "update item with same preferenceId and subscriber" in {
      val pcId = new ObjectId()
      val pId = new ObjectId()
      val a = PreferencesChangedRef(pcId, pId, "https://localhost:1234")

      val wi1 = repository.pushUpdated(a).futureValue
      val wi2 = repository.pushUpdated(a).futureValue

      wi1.updatedAt.isBefore(wi2.updatedAt) mustBe true
    }

    "create new item if same preferenceId and subscriber item is already in progress" in {
      val prefId = new ObjectId()
      val a =
        PreferencesChangedRef(new ObjectId(), prefId, "https://localhost:1234")
      val b =
        PreferencesChangedRef(new ObjectId(), prefId, "https://localhost:1234")

      val wi1 = repository.pushUpdated(a).futureValue
      repository.markAs(wi1.id, ProcessingStatus.InProgress)
      val wi2 = repository.pushUpdated(b).futureValue // creates a new workitem

      wi1.receivedAt.isBefore(wi2.receivedAt) mustBe true
      repository.collection.countDocuments().toFuture().futureValue mustBe (2)
    }

  }

}
