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

package uk.gov.hmrc.preferenceschangednotifier.scheduled

import org.apache.commons.lang3.time.StopWatch
import play.api.{Configuration, Logger}
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.preferenceschangednotifier.scheduling.{
  LockedScheduledJob,
  Result
}
import uk.gov.hmrc.preferenceschangednotifier.service.PublishSubscribersService

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

// $COVERAGE-OFF$Disabling test coverage
@Singleton
class PublishSubscribersJob @Inject()(
    lockRepository: LockRepository,
    override val configuration: Configuration,
    service: PublishSubscribersService
) extends LockedScheduledJob {

  private val logger = Logger(getClass)

  override val name: String = getClass.getSimpleName

  override val releaseLockAfter: Duration =
    lockDuration.getOrElse(Duration("60 seconds"))

  override val lockRepo: LockRepository = lockRepository

  override def executeInLock(implicit ec: ExecutionContext): Future[Result] =
    if (taskEnabled) {
      val stopWatch = StopWatch.createStarted()
      val result = service.execute
      result.foreach(r => {
        stopWatch.stop()
        logger.debug(
          s"Job $name complete: [stopwatch $stopWatch] [${r.message}]")
      })
      result
    } else {
      Future(Result(s"$name job is not enabled"))
    }
}
// $COVERAGE-ON$
