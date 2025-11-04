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

package uk.gov.hmrc.preferenceschangednotifier.service

import com.github.tomakehurst.wiremock.client.WireMock.{ aResponse, post, urlEqualTo }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.bson.types.ObjectId
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.bson.BsonDateTime
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.InsertOneOptions
import org.mongodb.scala.result.InsertOneResult
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.EitherValues
import org.scalatestplus.play.PlaySpec
import play.api.Application
import play.api.http.Status.OK
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.preferenceschangednotifier.WireMockUtil
import uk.gov.hmrc.preferenceschangednotifier.config.PublishSubscribersServiceConfig
import uk.gov.hmrc.preferenceschangednotifier.connectors.{ EpsHodsAdapterConnector, Subscriber, UpdatedPrintSuppressionsConnector }
import uk.gov.hmrc.preferenceschangednotifier.model.MessageDeliveryFormat.Paper
import uk.gov.hmrc.preferenceschangednotifier.model.{ EntityId, PreferencesChanged, PreferencesChangedRef }
import uk.gov.hmrc.preferenceschangednotifier.repository.{ PreferencesChangedRepository, PreferencesChangedWorkItemRepository }

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PublishSubscribersServiceMissingEntityISpec
    extends PlaySpec with ScalaFutures with IntegrationPatience with EitherValues with WireMockUtil with MongoSupport {

  "Service..." must {

    "skip invalid item" in new TestSetup {
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(OK))
          .build()
      )

      val docCount = pcwiRepo.collection.countDocuments().toFuture().futureValue

      val _ = service.execute().futureValue
      docCount must be(1)
    }

    "process the second item correctly" in new TestSetup {
      wireMockServer.addStubMapping(
        post(urlEqualTo("/eps-hods-adapter/preferences/notify-subscriber"))
          .willReturn(aResponse().withStatus(OK))
          .build()
      )

      // create and insert a PreferencesChanged document
      pcRepo.upsert(pc).futureValue

      // create and insert a PreferencesChangedRef workitem document
      val pcrDocument = createPcr(pc)
      val workitem = pcwiRepo.pushUpdated(pcrDocument).futureValue

      // there are now 2 workitems and a single valid PC document
      val docCount = pcwiRepo.collection.countDocuments().toFuture().futureValue

      val _ = service.execute().futureValue
      docCount must be(2)
    }
  }

  private def createPcr(pcDocument: PreferencesChanged) =
    PreferencesChangedRef(
      preferenceChangedId = pcDocument._id,
      preferenceId = pcDocument.preferenceId,
      entityId = pcDocument.entityId,
      subscriber = "EpsHodsAdapter"
    )

  trait TestSetup {

    val app: Application =
      GuiceApplicationBuilder()
        .configure(
          "metrics.enabled"                                       -> false,
          "preferencesChanged.retryFailedAfter"                   -> 10,
          "microservice.services.eps-hods-adapter.host"           -> "localhost",
          "microservice.services.eps-hods-adapter.port"           -> wireMockServer.port(),
          "scheduling.PublishSubscribersJob.taskEnabled"          -> false,
          "microservice.services.updated-print-suppressions.host" -> "localhost",
          "microservice.services.updated-print-suppressions.port" -> wireMockServer.port()
        )
        .build()

    implicit lazy val system: ActorSystem = ActorSystem()
    implicit lazy val materializer: Materializer = Materializer(system)

    val publisher = app.injector.instanceOf[PublishSubscribersPublisher]
    val auditConnector = app.injector.instanceOf[AuditConnector]

    val pcRepo = new PreferencesChangedRepository(mongoComponent, app.configuration)
    val pcwiRepo = new PreferencesChangedWorkItemRepository(mongoComponent, app.configuration)

    val epsSubscriber: Subscriber = app.injector.instanceOf[EpsHodsAdapterConnector]
    val upsSubscriber: Subscriber = app.injector.instanceOf[UpdatedPrintSuppressionsConnector]

    private val lockRepository = app.injector.instanceOf[LockRepository]
    private val applicationLifecycle = app.injector.instanceOf[ApplicationLifecycle]
    private val config = app.injector.instanceOf[PublishSubscribersServiceConfig]

    val service = new PublishSubscribersService(
      new PreferencesChangedService(
        pcRepo,
        pcwiRepo,
        Seq(epsSubscriber, upsSubscriber)
      ),
      publisher,
      auditConnector,
      lockRepository,
      applicationLifecycle,
      config
    )

    pcRepo.collection.drop().toFuture().futureValue
    pcwiRepo.collection.drop().toFuture().futureValue

    // push an item into the pc repo
    val prefId = new ObjectId()
    val entityId = EntityId.generate()

    val pc = PreferencesChanged(
      _id = new ObjectId(),
      changedValue = Paper,
      preferenceId = prefId,
      entityId = entityId,
      updatedAt = Instant.now(),
      taxIds = Map("nino" -> "AB112233C"),
      bounced = true
    )

    // insert a preference changed document
    val preferenceChangedRes = pcRepo.upsert(pc).futureValue
    implicit val formatter: Format[Instant] = MongoJavatimeFormats.instantFormat

    def createInvalidWorkitem(): Document =
      Document(
        "receivedAt"   -> BsonDateTime(Instant.now().toEpochMilli),
        "updatedAt"    -> BsonDateTime(Instant.now().toEpochMilli),
        "status"       -> "todo",
        "failureCount" -> 0,
        "item" -> Document(
          "preferenceChangedId" -> new ObjectId,
          "preferenceId"        -> new ObjectId,
          "subscriber"          -> "subscriber"
        )
      )

    insertWorkitem(
      createInvalidWorkitem()
    ).futureValue

    def insertWorkitem(doc: Document): Future[InsertOneResult] =
      mongoComponent.database
        .getCollection("preferencesChangedWorkItem")
        .insertOne(
          document = doc,
          options = InsertOneOptions()
        )
        .toFuture()
  }
}
