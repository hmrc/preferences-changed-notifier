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

import play.api.Logging
import play.api.http.Status.TOO_MANY_REQUESTS
import play.api.libs.json.{ JsObject, JsString, Json }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.http.UpstreamErrorResponse.{ Upstream4xxResponse, Upstream5xxResponse }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, PermanentlyFailed }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent
import uk.gov.hmrc.preferenceschangednotifier.connectors.Subscriber
import uk.gov.hmrc.preferenceschangednotifier.model.{ NotifySubscriberRequest, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import java.util.UUID
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class PublishSubscribersPublisher @Inject() (
  service: PreferencesChangedService,
  subscribers: Seq[Subscriber],
  auditConnector: AuditConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private type PCR = PreferencesChangedRef

  def execute(
    req: NotifySubscriberRequest,
    workItem: WorkItem[PCR]
  ): Future[Either[String, Result]] =
    getSubscriber(workItem) match {
      case None             => missingSubscriber(workItem)
      case Some(subscriber) => processNotification(req, subscriber, workItem)
    }

  private def processNotification(
    req: NotifySubscriberRequest,
    subscriber: Subscriber,
    workItem: WorkItem[PCR]
  ): Future[Either[String, Result]] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    subscriber
      .notifySubscriber(req)
      .flatMap {
        case Right(r: HttpResponse) =>
          service
            .completeAndDelete(workItem)
            .map { _ =>
              Right(
                Result(
                  s"Completed & deleted workitem:" +
                    s" ${workItem.id} successfully: HttpResponse status=${r.status}"
                )
              )
            }

        // Treat 429 as recoverable - mean work-item will be retried
        case Left(e @ UpstreamErrorResponse(message, TOO_MANY_REQUESTS, _, _)) =>
          processRecoverable(workItem, subscriber, e)

        // All other 4XX responses cannot be retried, so will be marked as permanently failed
        case Left(Upstream4xxResponse(e)) =>
          processUnrecoverable(workItem, subscriber, e)

        // All 5XX responses are retried
        case Left(Upstream5xxResponse(e)) =>
          processRecoverable(workItem, subscriber, e)

        case res =>
          val msg = s"publish to subscriber ${subscriber.toString} returned unexpected result: $res"
          val processingStatus = Failed
          logger.error(msg)
          service
            .completeWithStatus(workItem, processingStatus)
            .map { _ =>
              audit(buildAuditEvent(workItem, processingStatus, msg, subscriber))
              Left(msg)
            }
      }
      .recoverWith { case ex =>
        recoverNotify(workItem, subscriber, ex)
      }

  }

  private def recoverNotify(
    workItem: WorkItem[PCR],
    subscriber: Subscriber,
    ex: Throwable
  ): Future[Either[String, Result]] = {
    val processingStatus = Failed
    service
      .completeWithStatus(workItem, processingStatus)
      .map { isOk =>
        val msg = s"updated workitem: $isOk, status: $processingStatus\n" +
          s"Notify error, marking workitem [${workItem.id}] as Failed\nException: $ex"
        audit(buildAuditEvent(workItem, processingStatus, msg, subscriber))
        Left(msg)
      }
  }

  private def missingSubscriber(workItem: WorkItem[PCR]): Future[Either[String, Result]] = {
    logger.warn(s"Unknown subscriber: ${workItem.item.subscriber}; valid subscribers are: ${subscribers
        .foreach(_.name)}")
    service
      .completeWithStatus(workItem, PermanentlyFailed)
      .map { _ =>
        Left(
          s"Workitem [id: ${workItem.id}] marked as permanently failed:" +
            s" subscriber invalid [${workItem.item.subscriber}]"
        )
      }
  }

  private def getSubscriber(workItem: WorkItem[PreferencesChangedRef]): Option[Subscriber] =
    subscribers.find(s => s.name == workItem.item.subscriber)

  private def processRecoverable(
    workItem: WorkItem[PCR],
    subscriber: Subscriber,
    e: UpstreamErrorResponse
  ): Future[Either[String, Result]] = {
    val (message, processingState) =
      if (workItem.failureCount > 10) {
        val msg: String = s"publish to subscriber ${subscriber.name}" +
          s" failed ${workItem.failureCount} times, marking as permanently failed\nError: $e"
        logger.error(msg)
        (msg, PermanentlyFailed)
      } else {
        val msg =
          s"publish to subscriber ${subscriber.name} failed, with HTTP response: [${e.message}], will retry"
        logger.debug(msg)
        (msg, Failed)
      }

    service
      .completeWithStatus(workItem, processingState)
      .map { (a: Boolean) =>
        audit(buildAuditEvent(workItem, processingState, message, subscriber))
        Left(s"$message. Workitem updated $a")
      }
  }

  private def processUnrecoverable(
    workItem: WorkItem[PCR],
    subscriber: Subscriber,
    e: UpstreamErrorResponse
  ): Future[Either[String, Result]] = {
    val msg = s"publish to subscriber ${subscriber.name} permanently failed returning $e"
    logger.error(msg)

    service
      .completeWithStatus(workItem, PermanentlyFailed)
      .map { (a: Boolean) =>
        audit(buildAuditEvent(workItem, PermanentlyFailed, msg, subscriber))
        Left(s"$msg. Workitem updated $a")
      }
  }

  private def buildAuditEvent(
    workItem: WorkItem[PCR],
    processingStatus: ProcessingStatus,
    msg: String,
    subscriber: Subscriber
  ): ExtendedDataEvent =
    ExtendedDataEvent(
      auditSource = "preferences-changed-notifier",
      auditType = "notify-subscriber-failed",
      detail = Json.obj(
        "preferenceChangedId" -> JsString(s"${workItem.item.preferenceChangedId}"),
        "preferenceId"        -> JsString(s"${workItem.item.preferenceId}"),
        "subscriber"          -> JsString(s"${subscriber.name}"),
        "status"              -> JsString(s"$processingStatus"),
        "error"               -> JsString(msg)
      )
    )

  private def audit(extendedDataEvent: ExtendedDataEvent): Unit =
    auditConnector.sendExtendedEvent(extendedDataEvent)
}
