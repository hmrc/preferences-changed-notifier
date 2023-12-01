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

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.data.EitherT
import play.api.{Configuration, Logging}
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.preferenceschangednotifier.model.{
  NotifySubscriberRequest,
  PreferencesChangedRef
}
import uk.gov.hmrc.preferenceschangednotifier.scheduling.Result

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PublishSubscribersService @Inject()(
    service: PreferencesChangedService,
    publisher: PublishSubscribersPublisher,
    configuration: Configuration
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging {

  lazy val retryFailedAfter: Duration =
    Duration(
      configuration.getMillis("preferencesChanged.retryFailedAfter"),
      TimeUnit.SECONDS
    )

  def execute: Future[Result] =
    Source
      .unfoldAsync[NotUsed, WorkItem[PreferencesChangedRef]](NotUsed)(
        _ =>
          service
            .pull(retryFailedAfter)
            .map(_.map(workItem => (NotUsed, workItem)))
      )
      .runFoldAsync(Result(""))((acc, wi) => processWorkItem(acc, wi))
      .recover {
        case ex =>
          logger.error(s"Recovery error $ex")
          Result(ex.getMessage)
      }

  private def processWorkItem(acc: Result,
                              workItem: WorkItem[PreferencesChangedRef]) = {
    logger.debug(s"processing workitem: $workItem")
    val res = for {
      pc <- EitherT(service.find(workItem.item.preferenceChangedId))
      result <- EitherT(
        publisher.execute(
          NotifySubscriberRequest(pc),
          workItem
        )
      )
    } yield result
    res.fold(
      left =>
        if (acc.message != "") Result(s"${acc.message}\n$left")
        else Result(s"$left"),
      right =>
        if (acc.message != "") Result(s"${acc.message}\n${right.message}")
        else Result(s"${right.message}")
    )
  }
}
