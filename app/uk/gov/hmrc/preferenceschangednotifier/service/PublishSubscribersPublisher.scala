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
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.http.UpstreamErrorResponse.{
  Upstream4xxResponse,
  Upstream5xxResponse
}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, PermanentlyFailed}
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.connectors.Subscriber
import uk.gov.hmrc.preferenceschangednotifier.model.{
  NotifySubscriberRequest,
  PreferencesChangedRef
}
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PublishSubscribersPublisher @Inject()(
    service: PreferencesChangedService,
    subscribers: Seq[Subscriber]
)(implicit ec: ExecutionContext)
    extends Logging {

  private type PCR = PreferencesChangedRef

  def execute(
      req: NotifySubscriberRequest,
      workItem: WorkItem[PCR]
  ): Future[Option[Result]] = {

    val maybeSubscriber = getSubscriber(workItem)
    maybeSubscriber match {
      case None             => missingSubscriber(workItem)
      case Some(subscriber) => processNotification(req, subscriber, workItem)
    }
  }

  private def processNotification(
      req: NotifySubscriberRequest,
      subscriber: Subscriber,
      workItem: WorkItem[PCR]): Future[Option[Result]] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    subscriber
      .notifySubscriber(req)
      .recover { case ex => recoverNotify(workItem, ex) }
      .flatMap {
        case Right(s) =>
          service.completeAndDelete(workItem)

        case Left(Upstream4xxResponse(e)) =>
          processUnrecoverable(workItem, subscriber, e)

        case Left(Upstream5xxResponse(e)) =>
          processRecoverable(workItem, subscriber, e)

        case Left(s) =>
          logger.warn(
            s"publish to subscriber ${subscriber.toString} returned unexpected response $s")
          service.completeWithStatus(workItem, Failed)

        case e => Future.successful(Result(s"Unexpected result: ${e.toString}"))
      }
      .map(Option(_))

  }

  private def recoverNotify(workItem: WorkItem[PCR], ex: Throwable) = {
    service.completeWithStatus(workItem, Failed)
    Future.successful(
      Option(Result(
        s"Notify error, marking workitem [${workItem.id}] as Failed\nException: $ex"))
    )
  }

  private def missingSubscriber(workItem: WorkItem[PCR]) = {
    logger.warn(
      s"Unknown subscriber: ${workItem.item.subscriber}; valid subscribers are: $subscribers")
    service.completeWithStatus(workItem, PermanentlyFailed)
    Future.successful(
      Option(
        Result(s"Workitem [id: ${workItem.id}] marked as permanently failed:" +
          s" subscriber invalid [${workItem.item.subscriber}]")))
  }

  private def getSubscriber(
      workItem: WorkItem[PreferencesChangedRef]): Option[Subscriber] =
    subscribers.find(s => {
      s.getClass.getSimpleName == workItem.item.subscriber
    })

  private def processRecoverable(
      workItem: WorkItem[PCR],
      subscriber: Subscriber,
      e: UpstreamErrorResponse
  ) = {
    if (workItem.failureCount > 10) {
      logger.error(s"publish to subscriber ${subscriber.toString}" +
        s"failed ${workItem.failureCount} times, marking as permanently failed\nError: $e")
      service.completeWithStatus(workItem, PermanentlyFailed)
      // TODO: AUDIT LOG
    } else {
      logger.debug(
        s"publish to subscriber ${subscriber.toString} failed, will retry")
      service.completeWithStatus(workItem, Failed)
    }
  }

  private def processUnrecoverable(
      workItem: WorkItem[PCR],
      subscriber: Subscriber,
      e: UpstreamErrorResponse
  ) = {
    logger.error(
      s"publish to subscriber ${subscriber.toString} permanently failed returning $e")
    service.completeWithStatus(workItem, PermanentlyFailed)
    // TODO: AUDIT LOG
  }

}
