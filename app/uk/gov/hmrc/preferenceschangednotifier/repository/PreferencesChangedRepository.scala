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
  IndexModel,
  IndexOptions,
  Indexes,
  ReplaceOptions
}
import org.mongodb.scala.result
import play.api.{Configuration, Logger}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.preferenceschangednotifier.model.PreferencesChanged

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PreferencesChangedRepository @Inject()(
    mongo: MongoComponent,
    config: Configuration)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[PreferencesChanged](
      mongoComponent = mongo,
      collectionName = "preferencesChanged",
      domainFormat = PreferencesChanged.format,
      indexes = List(
        IndexModel(
          Indexes.ascending("preferenceId"),
          IndexOptions().unique(true)
        ),
        IndexModel(
          Indexes.ascending("updatedAt"),
          IndexOptions().expireAfter(config.get[Long]("preferencesChanged.ttl"),
                                     TimeUnit.DAYS)
        )
      )
    ) {

  private val logger: Logger = Logger(getClass)

  def replace(item: PreferencesChanged): Future[result.UpdateResult] = {
    collection
      .replaceOne(
        filter = Filters.eq("preferenceId", item.preferenceId),
        replacement = item,
        options = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .recover { ex =>
        logger.error(s"Recover during replace $ex")
        throw ex
      }
  }
}