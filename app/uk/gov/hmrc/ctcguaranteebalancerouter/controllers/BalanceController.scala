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

package uk.gov.hmrc.ctcguaranteebalancerouter.controllers

import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.RouterBalanceRequest
import uk.gov.hmrc.ctcguaranteebalancerouter.models.RouterBalanceResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.services.AccessCodeService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.BalanceRetrievalService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.CountryExtractionService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton()
class BalanceController @Inject() (
  accessCodeService: AccessCodeService,
  balanceRetrievalService: BalanceRetrievalService,
  countryExtractionService: CountryExtractionService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with ErrorTranslator {

  def postBalance(grn: GuaranteeReferenceNumber): Action[RouterBalanceRequest] = Action.async[RouterBalanceRequest](parse.json[RouterBalanceRequest]) {
    implicit request =>
      (for {
        country <- countryExtractionService.extractCountry(grn).asPresentation
        _       <- accessCodeService.ensureAccessCodeValid(grn, request.body.accessCode, country).asPresentation
        balance <- balanceRetrievalService.getBalance(grn, country).asPresentation
      } yield balance).fold(
        presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError)),
        balance => Ok(Json.toJson(RouterBalanceResponse(balance)))
      )
  }
}
