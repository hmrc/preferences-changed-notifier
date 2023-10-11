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

package uk.gov.hmrc.preferenceschangednotifier.service

import cats.data.EitherT
import org.bson.types.ObjectId
import play.api.Logger
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.model.{
  ErrorResponse,
  PersistenceError,
  RequestError,
  PreferencesChanged,
  PreferencesChangedRef
}
import uk.gov.hmrc.preferenceschangednotifier.repository.{
  PreferencesChangedRepository,
  PreferencesChangedWorkItemRepository
}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
@Singleton
class PreferencesChangedService @Inject()(
    pcRepo: PreferencesChangedRepository,
    pcWorkItemRepo: PreferencesChangedWorkItemRepository
)(implicit val ec: ExecutionContext) {

  private val logger: Logger = Logger(getClass)

  def preferenceChanged(pcRequest: PreferencesChangedRequest)
    : EitherT[Future, ErrorResponse, Unit] = {
    for {
      id <- EitherT(addPreferenceChanged(pcRequest))
      b <- EitherT(addPreferenceChangedWorkItems(id, pcRequest))
    } yield b

  }

  private def addPreferenceChanged(pcRequest: PreferencesChangedRequest)
    : Future[Either[ErrorResponse, ObjectId]] = {
    Try {
      updateRepo(PreferencesChanged.from(pcRequest))
    } match {
      case Success(value) => value
      case Failure(ex) =>
        ex match {
          case _: IllegalArgumentException => // for invalid ObjectId format
            Future.successful(Left(RequestError(ex.toString)))
          case _ =>
            Future.successful(Left(PersistenceError(ex.toString)))
        }
    }
  }

  private def updateRepo(req: PreferencesChanged) = {
    pcRepo
      .replace(req)
      .map {
        case a if a.wasAcknowledged() && a.getUpsertedId == null =>
          logger.debug(s"existing preference changed")
          Right(req.preferenceId)
        case b if b.wasAcknowledged() && b.getUpsertedId != null =>
          logger.debug(s"new preference changed")
          Right(b.getUpsertedId.asObjectId().getValue)
        case e =>
          logger.error(s"Error during addPreferenceChanged $e")
          Left(PersistenceError(s"$e"))
      }
      .recover { ex =>
        logger.error(s"Recover during addPreferenceChanged $ex")
        Left(PersistenceError(ex.toString))
      }
  }

  private def addPreferenceChangedWorkItems(
      id: ObjectId,
      pc: PreferencesChangedRequest): Future[Either[ErrorResponse, Unit]] = {
    Try {
      val sub1Pref = PreferencesChangedRef(
                                           // TODO: Config for subscriber
                                           id,
                                           new ObjectId(pc.preferenceId),
                                           subscriber =
                                             "https://eps-hods-adapter:XXXX")

      val _ = pcWorkItemRepo.pushUpdated(sub1Pref)
    } match {
      case Success(_)  => Future.successful(Right(()))
      case Failure(ex) => Future.successful(Left(PersistenceError(ex.toString)))
    }
  }
}
