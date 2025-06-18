/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.preferenceschangednotifier.config

import play.api.Configuration
import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.*
import javax.inject.{ Inject, Singleton }

@Singleton
class PublishSubscribersServiceConfig @Inject() (configuration: Configuration) {
  val name = "PublishSubscribersJob"

  lazy val initialDelay: FiniteDuration =
    configuration
      .get[Duration](s"scheduling.$name.initialDelay")
      .toMillis
      .milliseconds

  lazy val interval: FiniteDuration =
    configuration
      .get[Duration](s"scheduling.$name.interval")
      .toMillis
      .milliseconds

  lazy val retryFailedAfter: Duration =
    configuration.get[Duration]("preferencesChanged.retryFailedAfter").asInstanceOf[FiniteDuration]

  lazy private val rateConfig =
    configuration.get[Configuration]("preferencesChanged.rateLimit")

  lazy val elements: Int =
    rateConfig.get[Int]("elements")
  lazy val duration: FiniteDuration =
    rateConfig.get[Duration]("per").asInstanceOf[FiniteDuration]

  lazy val lockDuration: Option[FiniteDuration] =
    configuration
      .getOptional[Duration](s"scheduling.$name.lockDuration")
      .flatMap(duration => Some(duration.toMillis.milliseconds))

  lazy val releaseLockAfter: Duration =
    lockDuration.getOrElse(Duration("60 seconds"))

  lazy val taskEnabled: Boolean =
    configuration.get[Boolean](s"scheduling.$name.taskEnabled")
}
