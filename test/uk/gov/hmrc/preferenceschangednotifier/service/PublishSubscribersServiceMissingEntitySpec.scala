/*
 * Copyright 2024 HM Revenue & Customs
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

///*
// * Copyright 2023 HM Revenue & Customs
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package uk.gov.hmrc.preferenceschangednotifier.service
//
//import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, givenThat, post, urlEqualTo}
//import org.bson.types.ObjectId
//import org.mongodb.scala.bson
//import org.mongodb.scala.bson.collection.immutable.Document
//import org.mongodb.scala.bson.{BsonDocument, ObjectId}
//import org.mongodb.scala.model.InsertOneOptions
//import org.mongodb.scala.result.InsertOneResult
//import org.scalatest.{BeforeAndAfterEach, EitherValues}
//import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
//import org.scalatestplus.play.PlaySpec
//import org.scalatestplus.play.guice.GuiceOneAppPerSuite
//import play.api.Application
//import play.api.http.Status.OK
//import play.api.inject.guice.GuiceApplicationBuilder
//import play.api.libs.json.Json
//import uk.gov.hmrc.mongo.test.MongoSupport
//import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
//import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
//import uk.gov.hmrc.preferenceschangednotifier.model.{PreferencesChanged, PreferencesChangedRef}
//import uk.gov.hmrc.preferenceschangednotifier.repository.{PreferencesChangedRepository, PreferencesChangedWorkItemRepository}
//import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
//import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
//
//import java.time.Instant
//import java.util.UUID
//import scala.annotation.unused
//import scala.concurrent.ExecutionContext.Implicits.global
//
//class PublishSubscribersServiceMissingEntitySpec
//    extends PlaySpec
//    with ScalaFutures
//    with GuiceOneAppPerSuite
//    with IntegrationPatience
//    with BeforeAndAfterEach
//    with EitherValues
//    with WireMockUtil
//    with MongoSupport {
//
//  override def fakeApplication(): Application =
//    GuiceApplicationBuilder()
//      .configure(
//        "metrics.enabled" -> false,
//        "microservice.services.eps-hods-adapter.host" -> "localhost",
//        "microservice.services.eps-hods-adapter.port" -> "22222",
//        "featureFlag.useUPS" -> true,
//      )
//      .build()
//
//  private val service = app.injector.instanceOf[PublishSubscribersService]
////  private val pcRepo = app.injector.instanceOf[PreferencesChangedRepository]
//  private val pcwiRepo = new PreferencesChangedWorkItemRepository(
//    mongoComponent,
//    fakeApplication().configuration)
////    app.injector.instanceOf[PreferencesChangedWorkItemRepository]
//
//  override def beforeEach(): Unit = {
////    pcRepo.collection.drop().toFuture().futureValue
//    pcwiRepo.collection.drop().toFuture().futureValue
//    super.beforeEach()
//  }
//
//  "Service..." must {
//
//    "return correctly when stub returns 200 OK" in new TestCase {
//      givenThat(
//        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
//          .willReturn(aResponse().withStatus(OK)))
//
//      val docCount = pcwiRepo.collection.countDocuments().toFuture().futureValue
//      val db = pcwiRepo.toString
//
//      val doc = pcwiRepo.collection.find().toFuture().futureValue
//      print(s">>>>> Count $docCount, Doc: $doc")
//
//      val result = service.execute.futureValue
//
////      result.message must include(
////        s"Completed & deleted workitem: ${wi.id} successfully: HttpResponse status=200")
//    }
//  }
//
//  private def createPcr(preferenceChangedId: ObjectId,
//                        @unused entityId: String,
//                        preferenceId: ObjectId) = {
//    PreferencesChangedRef(preferenceChangedId = preferenceChangedId,
//                          preferenceId = preferenceId,
//                          entityId = null,
//                          subscriber = "EpsHodsAdapter")
//  }
//
//  class TestCase {
//    // push an item into the pc repo
//    val prefId = new ObjectId()
//    val entityId = UUID.randomUUID().toString
//
//    val pc = PreferencesChanged(_id = new ObjectId(),
//                                changedValue = Paper,
//                                preferenceId = prefId,
//                                entityId = entityId,
//                                updatedAt = Instant.now(),
//                                taxIds = Map("nino" -> "AB112233C"))
//
//    // insert a preference changed document
////    val preferenceChangedRes =
////      pcRepo.upsert(pc).futureValue
//
////    val pcr = createPcr(preferenceChangedRes._id, entityId, prefId)
//
//    implicit val formatter = MongoJavatimeFormats.instantFormat
//
//
//    // insert a workitem
//    val wi: InsertOneResult = mongoComponent.database
//      .getCollection("preferencesChangedWorkItem")
//      .insertOne(
//        document = BsonDocument(
//          "receivedAt" -> Instant.now(),
//          "updatedAt" -> Instant.now(),
//          "status" -> "todo",
//          "failureCount" -> 0,
//          "item" -> BsonDocument("preferenceChangedId" -> (new ObjectId).toString,
//                                 "preferenceId" -> (new ObjectId).toString,
//                                 "subscriber" -> "subscriber")
//        ),
//        options = InsertOneOptions()
//      )
//      .toFuture()
//      .futureValue
//  }
//}
