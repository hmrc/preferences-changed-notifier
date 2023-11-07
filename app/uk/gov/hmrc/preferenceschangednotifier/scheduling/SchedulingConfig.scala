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

import play.api.Configuration

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

trait SchedulingConfig {
  val configuration: Configuration

  val name: String

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

  lazy val lockDuration: Option[FiniteDuration] =
    configuration
      .getOptional[Duration](s"scheduling.$name.lockDuration")
      .flatMap(duration => Some(duration.toMillis.milliseconds))

  lazy val taskEnabled: Boolean =
    configuration
      .getOptional[Boolean](s"scheduling.$name.taskEnabled")
      .getOrElse(false)

  class WriteDuration(val d: Duration) {
    def toHM: String = {

      def print(value: Long, unit: TimeUnit): Option[String] =
        (value, unit) match {
          case (0, _) => None
          case (v, u) => Some(s"$v ${u.toString.toLowerCase()}")
        }

      val time = List[Option[String]](
        print(d.toHours, TimeUnit.HOURS),
        print(d.toMinutes % 60, TimeUnit.MINUTES),
        print(d.toSeconds % 60, TimeUnit.SECONDS)
      )
      val m = time.filter(_.isDefined).collect[String](a => a.get)
      s"${m.mkString(" ")}"
    }
  }

  implicit def durationToString(d: Duration): WriteDuration =
    new WriteDuration(d)

  override def toString: String =
    s"'$name' initialDelay: ${initialDelay.toHM} interval: ${interval.toHM}"
}
