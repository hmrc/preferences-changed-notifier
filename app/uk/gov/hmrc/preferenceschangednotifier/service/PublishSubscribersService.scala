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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.preferenceschangednotifier.model.{ NotifySubscriberRequest, PreferencesChanged, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@Singleton
class PublishSubscribersService @Inject() (
  service: PreferencesChangedService,
  publisher: PublishSubscribersPublisher,
  auditConnector: AuditConnector,
  configuration: Configuration
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  lazy val retryFailedAfter: Duration =
    Duration(
      configuration.getMillis("preferencesChanged.retryFailedAfter"),
      TimeUnit.SECONDS
    )

  /** Called by scheduler to find items for workload processing. Workloads will call any registered subscribers to
    * notify them of a changed optin/optout value.
    */
  def execute: Future[Result] =
    Source
      .unfoldAsync[NotUsed, WorkItem[PreferencesChangedRef]](NotUsed)(_ =>
        service
          .pull(retryFailedAfter)
          .map(_.map(workItem => (NotUsed, workItem)))
      )
      .runFoldAsync(Result(""))((acc, wi) => processWorkItem(acc, wi))
      .recover { case ex =>
        logger.error(s"Recovery error $ex")
        Result(ex.getMessage)
      }

  private def processWorkItem(acc: Result, workItem: WorkItem[PreferencesChangedRef]): Future[Result] = {
    logger.debug(s"processing workitem: $workItem")

    Try {
      service
        .find(workItem.item.preferenceChangedId)
        .flatMap {
          case Right(pc) =>
            val res = execute(pc, workItem)
            res.map(r => Result(s"${r.message}\n${acc.message}"))
          case Left(msg) =>
            audit(workItem, msg)
            Future.successful(Result(s"$msg ${acc.message}"))
        }
    } match {
      case Success(value) => value
      case Failure(exception) =>
        Future.successful(Result(s"${exception.getMessage}"))
    }
  }

  private def execute(pc: PreferencesChanged, workItem: WorkItem[PreferencesChangedRef]): Future[Result] =
    publisher
      .execute(NotifySubscriberRequest(pc), workItem)
      .map {
        case Right(result) =>
          audit(pc)
          result
        case Left(msg) =>
          audit(pc, msg)
          Result(msg)
      }

  private def audit(pc: PreferencesChanged, msg: String): Unit = {
    logger.error(s"Failed to notify subscriber: $msg")
    auditConnector.sendExplicitAudit(
      auditType = "notify-subscriber-error",
      detail = Map(
        "nino"         -> s"${pc.taxIds.getOrElse("nino", "Not found")}",
        "saUtr"        -> s"${pc.taxIds.getOrElse("sautr", "Not found")}",
        "preferenceId" -> s"${pc.preferenceId}",
        "error"        -> msg
      )
    )
  }

  private def audit(pc: PreferencesChanged): Unit =
    auditConnector.sendExplicitAudit(
      auditType = "notify-subscriber-success",
      detail = Map(
        "nino"         -> s"${pc.taxIds.getOrElse("nino", "Not found")}",
        "saUtr"        -> s"${pc.taxIds.getOrElse("sautr", "Not found")}",
        "preferenceId" -> s"${pc.preferenceId}",
        "printStatus"  -> pc.changedValue.name
      )
    )

  private def audit(workItem: WorkItem[PreferencesChangedRef], msg: String): Unit = {
    logger.error(s"Failed to find preferencesChanged document: $msg")
    auditConnector.sendExplicitAudit(
      auditType = "notify-subscriber-error",
      detail = Map(
        "preferenceChangedId" -> s"${workItem.item.preferenceChangedId}",
        "preferenceId"        -> s"${workItem.item.preferenceId}",
        "subscriber"          -> s"${workItem.item.subscriber}",
        "workitemStatus"      -> s"${workItem.status}",
        "error"               -> msg
      )
    )
  }
}
