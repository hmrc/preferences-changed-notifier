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


import org.mongodb.scala.bson.ObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{BeforeAndAfterEach, EitherValues, TestSuite}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import play.api.test.Injecting
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.preferenceschangednotifier.connectors.{EpsHodsAdapterConnector, Subscriber}
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.{PreferencesChanged, PreferencesChangedRef}
import uk.gov.hmrc.preferenceschangednotifier.repository.{PreferencesChangedRepository, PreferencesChangedWorkItemRepository}

import java.time.Instant
import java.util.UUID


class PublishSubscribersServiceISpec
  extends AnyFreeSpec
  with Matchers
  with ScalaFutures
  with MongoSupport
  with BeforeAndAfterEach
  with EitherValues
  with TestSuite
  with GuiceOneServerPerSuite
  with IntegrationPatience
  with Injecting {
  spec =>

  override implicit lazy val app: Application = new GuiceApplicationBuilder()
    .configure(
      "metrics.enabled" -> false,
      "preferencesChanged.retryFailedAfter" -> 10)
    .build()

  private val service     = inject[PublishSubscribersService]
  private val connector   = inject[EpsHodsAdapterConnector]
  private val pcRepo      = inject[PreferencesChangedRepository]
  private val pcwiRepo    = inject[PreferencesChangedWorkItemRepository]
  
  val subscribers : Seq[Subscriber] = Seq(connector)

  val EpsSubscriber: String = "EpsHodsAdapter"
  val UpsSubscriber: String = "UpdatedPrintSuppressions"

  override def beforeEach(): Unit = {
    pcRepo.collection.drop().toFuture().futureValue
    pcwiRepo.collection.drop().toFuture().futureValue
    super.beforeEach()
  }
  
  private def createPcr(preferenceChangedId: ObjectId, preferenceId: ObjectId, entityId: String, subscriber: String) = {
    PreferencesChangedRef(
      preferenceChangedId = preferenceChangedId,
      preferenceId        = preferenceId,
      entityId            = entityId,
      subscriber          = subscriber)
  }
  
  "publish subscribers service" - {

    "should process the workitem for both subscribers" in {
      // push an item into the pc repo
      val prefId = new ObjectId()
      val entityId = UUID.randomUUID().toString

      val pc = PreferencesChanged(
        _id = new ObjectId,
        changedValue = Paper,
        preferenceId = prefId,
        entityId     = entityId,
        updatedAt = Instant.now(),
        taxIds = Map("nino" -> "YY000200A", "sautr" -> "SAUTR1"))

      val preferenceChangedRes = pcRepo.upsert(pc).futureValue
      val pcrEps = createPcr(preferenceChangedRes._id, prefId, entityId, EpsSubscriber)
      val pcrUps = createPcr(preferenceChangedRes._id, prefId, entityId, UpsSubscriber)

      val wi1 = pcwiRepo.pushUpdated(pcrEps).futureValue
      val wi2 = pcwiRepo.pushUpdated(pcrUps).futureValue

      val countItems =
        pcwiRepo.collection.countDocuments().toFuture().futureValue

      countItems must be(2)

      val result = service.execute.futureValue
      result.message must include(s"Completed & deleted workitem: ${wi1.id} successfully: HttpResponse status=200")
      result.message must include(s"Completed & deleted workitem: ${wi2.id} successfully: HttpResponse status=200")

      val postExecuteCount =
        pcwiRepo.collection.countDocuments().toFuture().futureValue
      postExecuteCount must be(0)
    }

    
    "should receive 400 for missing SaUtr" in {
      // push an item into the pc repo
      val prefId= new ObjectId()
      val entityId = UUID.randomUUID().toString
      
      val pc= PreferencesChanged(
        _id = new ObjectId,
        entityId = entityId,
        changedValue = Paper,
        preferenceId = prefId,
        updatedAt = Instant.now(),
        taxIds = Map("nino" -> "YY000200A"))

      val preferenceChangedRes = pcRepo.upsert(pc).futureValue
      val pcrEps = createPcr(preferenceChangedRes._id, prefId, entityId, EpsSubscriber)
      val pcrUps = createPcr(preferenceChangedRes._id, prefId, entityId, UpsSubscriber)
      
      val wi1 = pcwiRepo.pushUpdated(pcrEps).futureValue
      val _   = pcwiRepo.pushUpdated(pcrUps).futureValue
      
      val countItems =
        pcwiRepo.collection.countDocuments().toFuture().futureValue

      countItems must be(2)

      val result = service.execute.futureValue
      
      result.message must include(s"Completed & deleted workitem: ${wi1.id} successfully: HttpResponse status=200")
      result.message must include(s"permanently failed")
      result.message must include(s"Missing SaUtr")

      // There will be one item left which did not get processed successfully
      val postExecuteCount =
        pcwiRepo.collection.countDocuments().toFuture().futureValue
      postExecuteCount must be(1)
    }
  }
}
