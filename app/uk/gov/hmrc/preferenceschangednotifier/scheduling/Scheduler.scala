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

package uk.gov.hmrc.preferenceschangednotifier.scheduling

import akka.actor.{ActorSystem, Cancellable}
import play.api.Logging
import play.api.inject.ApplicationLifecycle

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Scheduler @Inject()(
    actorSystem: ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    scheduledJobs: ScheduledJobs
)(implicit ec: ExecutionContext)
    extends Logging {

  val runningJobs: Iterable[Cancellable] = scheduledJobs.jobs.map { job =>
    logger.info(s"Scheduling job [${job}]")

    actorSystem.scheduler.scheduleWithFixedDelay(job.initialDelay,
                                                 job.interval)(new Runnable() {
      override def run(): Unit =
        job.execute.map { _ =>
          logger.info(s"Scheduled Job [${job.name}]: Completed Successfully")
        } recover {
          case t: Throwable =>
            logger.error(s"Scheduled Job [${job.name}]: Failed", t)
        }
    })
  }

  applicationLifecycle.addStopHook(() =>
    Future.successful(runningJobs.foreach(_.cancel())))

}
