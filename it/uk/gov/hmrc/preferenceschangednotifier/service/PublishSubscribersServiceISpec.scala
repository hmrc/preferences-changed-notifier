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


  override def beforeEach(): Unit = {
    pcRepo.collection.drop().toFuture().futureValue
    pcwiRepo.collection.drop().toFuture().futureValue
  }
  
  private def createPcr(preferenceChangedId: ObjectId, preferenceId: ObjectId) = {
    PreferencesChangedRef(
      preferenceChangedId = preferenceChangedId,
      preferenceId        = preferenceId,
      subscriber          = "EpsHodsAdapterConnector")
  }
  
  "publish subscribers service" - {

    "should process the workitem" in {
      // push an item into the pc repo
      val prefId= new ObjectId()
      
      val pc= PreferencesChanged(
        changedValue = Paper,
        preferenceId = prefId,
        updatedAt = Instant.now(),
        taxIds = Map("nino" -> "AB112233C"))

      val preferenceChangedRes = pcRepo.replace(pc).futureValue
      
      val pcr  = createPcr(
        preferenceChangedRes.getUpsertedId.asObjectId().getValue, prefId)
      
      val wi = pcwiRepo.pushUpdated(pcr).futureValue

      val result = service.execute.futureValue
      result.message must include(s"Notify error, marking workitem [${wi.id}] as Failed")
      println(s"===== $result")
//      result.message must include("error occurred processing subscriber")
    }
  }
}
