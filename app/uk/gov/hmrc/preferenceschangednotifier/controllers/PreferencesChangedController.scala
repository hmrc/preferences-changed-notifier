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

package uk.gov.hmrc.preferenceschangednotifier.controllers

import play.api.libs.json.{ JsError, JsSuccess, JsValue, Json, Reads }
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import play.api.mvc.{ Action, ControllerComponents }
import uk.gov.hmrc.preferenceschangednotifier.controllers.model.PreferencesChangedRequest
import uk.gov.hmrc.preferenceschangednotifier.service.PreferencesChangedService
import uk.gov.hmrc.preferenceschangednotifier.model.{ PersistenceError, RequestError, ServerError }

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class PreferencesChangedController @Inject() (
  cc: ControllerComponents,
  svc: PreferencesChangedService
)(implicit val ec: ExecutionContext)
    extends BackendController(cc) {

  import PreferencesChangedRequest.given_Reads_PreferencesChangedRequest

  def preferencesChanged(): Action[JsValue] = Action.async(parse.tolerantJson) { implicit request =>
    request.body.validate[PreferencesChangedRequest] match {
      case JsSuccess(body, _) =>
        svc
          .preferenceChanged(body)
          .fold(
            {
              case RequestError(r)     => BadRequest(r)
              case PersistenceError(p) => InternalServerError(p)
              case ServerError(s)      => InternalServerError(s)
            },
            _ => Ok
          )
      case JsError(errors) =>
        Future.successful(BadRequest(s"Invalid JSON format, ${JsError.toJson(errors)}"))
    }
  }
}
