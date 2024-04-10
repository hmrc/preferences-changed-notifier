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
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.http.UpstreamErrorResponse.{ Upstream4xxResponse, Upstream5xxResponse }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, PermanentlyFailed }
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.connectors.Subscriber
import uk.gov.hmrc.preferenceschangednotifier.model.{ NotifySubscriberRequest, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class PublishSubscribersPublisher @Inject() (
  service: PreferencesChangedService,
  subscribers: Seq[Subscriber]
)(implicit ec: ExecutionContext)
    extends Logging {

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

        case Left(Upstream4xxResponse(e)) =>
          processUnrecoverable(workItem, subscriber, e)

        case Left(Upstream5xxResponse(e)) =>
          processRecoverable(workItem, subscriber, e)

        case Left(UpstreamErrorResponse(message, status, _, _)) =>
          logger.error(s"publish to subscriber ${subscriber.toString} returned unexpected response: $status, $message")
          service
            .completeWithStatus(workItem, Failed)
            .map(_ => Left(s"with http response: $status, $message"))

        case e =>
          logger.error(s"publish to subscriber ${subscriber.toString} returned unexpected result: $e")
          service
            .completeWithStatus(workItem, Failed)
            .map(_ => Left(s"Unexpected result: ${e.toString}"))
      }
      .recoverWith { case ex =>
        recoverNotify(workItem, ex)
      }

  }

  private def recoverNotify(workItem: WorkItem[PCR], ex: Throwable): Future[Either[String, Result]] =
    service
      .completeWithStatus(workItem, Failed)
      .map { r =>
        Left(
          s"$r\nNotify error, marking workitem [${workItem.id}] as Failed\nException: $ex"
        )
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
  ): Future[Either[String, Result]] =
    if (workItem.failureCount > 10) {
      val msg: String = s"publish to subscriber ${subscriber.toString}" +
        s"failed ${workItem.failureCount} times, marking as permanently failed\nError: $e"

      logger.error(msg)
      service
        .completeWithStatus(workItem, PermanentlyFailed)
        .map { a: Boolean =>
          Left(s"$msg. Workitem updated $a")
        }
      // TODO: AUDIT LOG
    } else {
      val msg =
        s"publish to subscriber ${subscriber.toString} failed, with HTTP response: [${e.message}], will retry"
      logger.debug(msg)

      service
        .completeWithStatus(workItem, Failed)
        .map { a: Boolean =>
          Left(s"$msg. Workitem updated $a")
        }

    }

  private def processUnrecoverable(
    workItem: WorkItem[PCR],
    subscriber: Subscriber,
    e: UpstreamErrorResponse
  ): Future[Either[String, Result]] = {
    val msg =
      s"publish to subscriber ${subscriber.toString} permanently failed returning $e"
    logger.error(msg)

    service
      .completeWithStatus(workItem, PermanentlyFailed)
      .map { a: Boolean =>
        Left(s"$msg. Workitem updated $a")
      }
    // TODO: AUDIT LOG
  }

}
