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
import org.mongodb.scala.model.Filters
import play.api.Logging
import uk.gov.hmrc.mongo.workitem.{ResultStatus, WorkItem}
import uk.gov.hmrc.preferenceschangednotifier.connectors.Subscriber
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.model.{
  ErrorResponse,
  PersistenceError,
  PreferencesChanged,
  PreferencesChangedRef,
  RequestError
}
import uk.gov.hmrc.preferenceschangednotifier.repository.{
  PreferencesChangedRepository,
  PreferencesChangedWorkItemRepository
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class PreferencesChangedService @Inject()(
    pcRepo: PreferencesChangedRepository,
    pcWorkItemRepo: PreferencesChangedWorkItemRepository,
    subscribers: Seq[Subscriber]
)(implicit val ec: ExecutionContext)
    extends Logging {
  def completeAndDelete(
      workItem: WorkItem[PreferencesChangedRef]): Future[Boolean] =
    pcWorkItemRepo.completeAndDelete(workItem.id)

  def completeWithStatus(workItem: WorkItem[PreferencesChangedRef],
                         status: ResultStatus): Future[Boolean] =
    pcWorkItemRepo.complete(workItem.id, status)

  def find(preferenceChangedId: ObjectId)
    : Future[Either[String, PreferencesChanged]] =
    pcRepo.collection
      .find(Filters.eq("_id", preferenceChangedId))
      .toSingle()
      .toFutureOption()
      .map(a =>
        Either
          .cond(a.isDefined, a.get, s"_id ${preferenceChangedId} not found"))

  def pull(retryFailedAfter: Duration)
    : Future[Option[WorkItem[PreferencesChangedRef]]] =
    pcWorkItemRepo.pullOutstanding(
      Instant.now().minus(retryFailedAfter.toSeconds, ChronoUnit.SECONDS),
      Instant.now()
    )

  def preferenceChanged(
      pcRequest: PreferencesChangedRequest,
      updateUPS: Boolean): EitherT[Future, ErrorResponse, Unit] = {

    for {
      pcId <- EitherT(addPreferenceChanged(pcRequest))
      res <- EitherT(
        addPreferenceChangedWorkItems(pcId,
                                      new ObjectId(pcRequest.preferenceId),
                                      updateUPS))
    } yield res

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

  // Add or update the preference changed document
  private def updateRepo(req: PreferencesChanged) = {
    pcRepo
      .upsert(req)
      .map { doc =>
        logger.debug(s"existing preference changed")
        Right(doc._id)
      }
      .recover { ex =>
        logger.error(s"Recover during addPreferenceChanged $ex")
        Left(PersistenceError(ex.toString))
      }
  }

  private def filterSubscribers(updateUPS: Boolean) = {
    val items = subscribers.filterNot((p: Subscriber) => {
      (p.name == "UpdatedPrintSuppressions") && (updateUPS == false)
    })
    items
  }

  // Create a workitem for the specified preference changed
  private def addPreferenceChangedWorkItems(
      pcId: ObjectId,
      pId: ObjectId,
      updateUPS: Boolean): Future[Either[ErrorResponse, Unit]] = {

    Future
      .sequence {
        filterSubscribers(updateUPS).map { s =>
          pcWorkItemRepo
            .pushUpdated(
              PreferencesChangedRef(pcId, pId, s.name)
            )
            .map(_ => Right(()))
            .recover { ex =>
              Left(PersistenceError(ex.getMessage))
            }

        }
      }
      .map { seq =>
        {
          if (seq.exists(_.isLeft)) {
            Left(
              seq
                .collect { case Left(ex) => ex }
                .fold(PersistenceError(""))((acc, next) =>
                  PersistenceError(s"${acc.message}\n${next.message}")))
          } else {
            Right(())
          }
        }
      }
  }

}
