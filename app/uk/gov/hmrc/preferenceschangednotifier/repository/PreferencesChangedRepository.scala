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

import org.mongodb.scala.model.ReturnDocument.AFTER
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.{ Filters, FindOneAndUpdateOptions, IndexModel, IndexOptions, Indexes, Updates }
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChanged

import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

/** Keeps a record of those preferences that have changed their optin/optout values. So if someone optsin, they will
  * have Digital delivery or optout will have Paper delivery. This is required because several downstream systems want
  * to know if the value has been changed. NPS: Via the eps-Hods-adapter service UPS: Via the updated-print-suppression
  * service
  */
@Singleton
class PreferencesChangedRepository @Inject() (mongo: MongoComponent, config: Configuration)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[PreferencesChanged](
      mongoComponent = mongo,
      collectionName = "preferencesChanged",
      domainFormat = PreferencesChanged.format,
      indexes = List(
        IndexModel(
          Indexes.ascending("entityId")
        ),
        IndexModel(
          Indexes.ascending("updatedAt"),
          IndexOptions().expireAfter(config.get[Long]("preferencesChanged.ttl"), TimeUnit.DAYS)
        )
      ),
      replaceIndexes = true
    ) {

  private val logger: Logger = Logger(getClass)

  /** Insert new update, unless already in existence for specified preferenceId
    *
    * @param item
    *   describes the document to insert or update
    * @return
    *   updated copy of the document
    */
  def upsert(item: PreferencesChanged): Future[PreferencesChanged] =
    collection
      .findOneAndUpdate(
        filter = Filters.eq("entityId", item.entityId),
        update = updates(item),
        options = FindOneAndUpdateOptions().upsert(true).returnDocument(AFTER)
      )
      .toFuture()
      .recover { ex =>
        logger.error(s"Recover during upsert: $ex")
        throw ex
      }

  // Create update statements for creating upsert
  private def updates(item: PreferencesChanged) =
    Updates.combine(
      // for updates, no need to specify preferenceId because we found it!
      Updates.set("changedValue", item.changedValue.name),
      Updates.set("bounced", item.bounced),
      Updates.set("updatedAt", item.updatedAt),
      Updates.set("taxIds", item.taxIds),
      Updates.set("preferenceId", item.preferenceId),
      // for new documents only, need to specify the doc id and pref id
      Updates.setOnInsert("_id", item._id),
      Updates.setOnInsert("entityId", item.entityId)
    )
}
