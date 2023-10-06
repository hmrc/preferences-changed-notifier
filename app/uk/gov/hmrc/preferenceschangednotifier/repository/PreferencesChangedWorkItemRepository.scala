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

import org.mongodb.scala.model.{
  Filters,
  FindOneAndUpdateOptions,
  IndexModel,
  IndexOptions,
  ReturnDocument,
  Updates
}
import org.mongodb.scala.model.Indexes.ascending
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.{
  ProcessingStatus,
  WorkItem,
  WorkItemFields,
  WorkItemRepository
}
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChangedRef

import java.time.{Duration, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
  * A work item represents an item in a queue which will represent a preferenceId and a subscriber.
  * There will be duplicates, and that is ok, typically when one is already in progress or completed.
  */
@Singleton
class PreferencesChangedWorkItemRepository @Inject()(
    mongoComponent: MongoComponent,
    configuration: Configuration
)(implicit ec: ExecutionContext)
    extends WorkItemRepository[PreferencesChangedRef](
      collectionName = "preferencesChangedWorkItem",
      mongoComponent = mongoComponent,
      itemFormat = PreferencesChangedRef.format,
      workItemFields = WorkItemFields.default,
      extraIndexes = Seq(
        IndexModel(
          ascending("item.preferenceId"),
          IndexOptions().unique(false).background(true)
        ),
        IndexModel(
          ascending("item.subscriber"),
          IndexOptions().unique(false).background(true)
        )
      ),
      replaceIndexes = false
    ) {

  override def inProgressRetryAfter: Duration =
    Duration.ofMillis(
      configuration.getMillis(
        "preferencesChangedWorkItems.retryInProgressAfter")
    )

  override def now(): Instant = Instant.now()

  def pushUpdated(preferencesChanged: PreferencesChangedRef)
    : Future[WorkItem[PreferencesChangedRef]] = {
    collection
      .findOneAndUpdate(
        filter = workItemFilter(preferencesChanged),
        update = update(),
        options = FindOneAndUpdateOptions()
          .upsert(false)
          .returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()
      .flatMap {
        case Some(wi) => Future.successful(wi)
        case None     => pushNew(preferencesChanged)
      }
  }

  private def update() = {
    Updates.set("updatedAt", now())
  }

  private def workItemFilter(preferencesChangedRef: PreferencesChangedRef) = {
    Filters.and(
      Filters.eq("item.preferenceId", preferencesChangedRef.preferenceId),
      Filters.eq("item.subscriber", preferencesChangedRef.subscriber),
      Filters.eq("status", ProcessingStatus.ToDo.name)
    )
  }
}
