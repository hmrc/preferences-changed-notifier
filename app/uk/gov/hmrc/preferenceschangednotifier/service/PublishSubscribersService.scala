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

import org.apache.pekko.{ Done, NotUsed }
import org.apache.pekko.stream.{ KillSwitch, KillSwitches, Materializer }
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source }
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates
import play.api.inject.ApplicationLifecycle
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{ LockRepository, LockService }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.PermanentlyFailed
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.preferenceschangednotifier.config.PublishSubscribersServiceConfig
import uk.gov.hmrc.preferenceschangednotifier.model.{ NotifySubscriberRequest, PreferencesChanged, PreferencesChangedRef, Result }

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@Singleton
class PublishSubscribersService @Inject() (
  service: PreferencesChangedService,
  publisher: PublishSubscribersPublisher,
  auditConnector: AuditConnector,
  lockRepo: LockRepository,
  lifecycle: ApplicationLifecycle, // Play's lifecycle hook
  config: PublishSubscribersServiceConfig
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging with LockService {

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private var killSwitch: Option[KillSwitch] = None

  override val lockRepository: LockRepository = lockRepo
  override val lockId: String = s"${config.name}-scheduled-job-lock"
  override val ttl: Duration = config.releaseLockAfter

  // Only run the stream if enabled in config
  if (config.taskEnabled) {
    start()
  }

  // Entrypoint
  def start(): Unit = {
    logger.warn(
      s"Stream starting: initialDelay: ${config.initialDelay}, interval: ${config.interval}, lock-ttl: $ttl, " +
        s"retryFailedAfter: ${config.retryFailedAfter}, rate-limit: [elements: ${config.elements}, per: ${config.duration}]"
    )

    val (killSwitch, streamDone) =
      // Tick source, generates a Unit element to start execution periodically
      Source
        .tick(config.initialDelay, config.interval, tick = ())
        .mapAsync(1)(_ =>
          logger.debug(s"-> Tick")
          startWorkloadStream()
        )
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run() // Run forever

    this.killSwitch = Some(killSwitch)

    // Register cleanup on shutdown
    lifecycle.addStopHook { () =>
      logger.warn("Shutting down publish subscribers stream...")
      killSwitch.shutdown() // Terminate the stream gracefully
      Future.successful(())
    }
  }

  // Attempt to acquire lock and run the body
  def startWorkloadStream(): Future[Unit] =
    // Acquire a lock
    withLock {
      logger.debug(s"Workload stream starting")
      // Execute this body when lock successfully acquired
      execute()
    }
      .map {
        case Some(result) =>
          logger.debug(s"Successfully processed work under lock: $result")
        case None =>
          logger.debug("Lock held by another instance; skipping")
      }
      .recover { case ex =>
        logger.error(s"Lock acquisition failed: $ex")
      }

  // Batch processing, streams one workitem at a time
  def execute(): Future[Unit] =
    // Generate a source by attempting to get a workitem
    Source
      .unfoldAsync(())(_ =>
        // Attempt to fetch a workitem one at a time.
        // If pull returns None, stream will close.
        service
          .pull(config.retryFailedAfter)
          .map(_.map(workItem => ((), workItem)))
      )
      // Apply rate limiting for downstream processing
      .throttle(config.elements, per = config.duration)
      .watchTermination() { (_, done) =>
        done.onComplete {
          case Success(_)  => logger.debug(s"Workload stream terminated")
          case Failure(ex) => logger.error(s"Workload stream terminated with error: ${ex.getMessage}")
        }
        done
      }
      .mapAsync(1)(processWorkItem)
      .run()
      .map(_ => ())
      .recover { case ex =>
        logger.error(s"Recovery error ${ex.getMessage}")
        Future.unit
      }

  private def processWorkItem(workItem: WorkItem[PreferencesChangedRef]): Future[Unit] = {
    logger.debug(s"Processing workitem: $workItem")

    Try {
      service
        .find(workItem.item.preferenceChangedId)
        .flatMap {
          case Right(pc) =>
            logger.debug(
              s"Preference changed record for workItem id: ${workItem.id} " +
                s"located, attempting to publish"
            )
            publish(pc, workItem)
          case Left(msg) =>
            logger.error(s"Failed to find preferencesChanged document for workItem id:${workItem.id} msg: $msg")
            audit(workItem, msg)
            service.completeWithStatus(workItem, PermanentlyFailed)
            Future.unit
        }
    }.getOrElse(Future.unit)
  }

  // Send a notification to the systems that are registered.
  // PreferencesChangedRef.subscriber determines system to send request to.
  private def publish(pc: PreferencesChanged, workItem: WorkItem[PreferencesChangedRef]): Future[Unit] =
    publisher
      .execute(NotifySubscriberRequest(pc), workItem)
      .map {
        case Right(result) =>
          logger.info(
            s"Publish to subscriber ${workItem.item.subscriber} completed for workItem id:${workItem.id}: $result"
          )
        case Left(msg) =>
          logger.error(s"Publish to subscriber ${workItem.item.subscriber} failed: $msg")
          audit(pc, msg)
      }

  private def audit(pc: PreferencesChanged, msg: String): Unit =
    auditConnector.sendExplicitAudit(
      auditType = "notify-subscriber-error",
      detail = Map(
        "nino"         -> s"${pc.taxIds.getOrElse("nino", "Not found")}",
        "saUtr"        -> s"${pc.taxIds.getOrElse("sautr", "Not found")}",
        "preferenceId" -> s"${pc.preferenceId}",
        "error"        -> msg
      )
    )

  private def audit(workItem: WorkItem[PreferencesChangedRef], msg: String): Unit =
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
