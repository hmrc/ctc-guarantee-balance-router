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
import uk.gov.hmrc.ctcguaranteebalancerouter.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses
import uk.gov.hmrc.ctcguaranteebalancerouter.models.requests.RouterBalanceRequest
import uk.gov.hmrc.ctcguaranteebalancerouter.services.AccessCodeService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.BalanceRetrievalService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.CountryExtractionService
import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton()
class BalanceController @Inject() (
  accessCodeService: AccessCodeService,
  balanceRetrievalService: BalanceRetrievalService,
  countryExtractionService: CountryExtractionService,
  cc: ControllerComponents,
  auth: InternalAuthActionProvider
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with ErrorTranslator {

  private val endpointPermission = Predicate.Permission(Resource(ResourceType("ctc-guarantee-balance-router"), ResourceLocation("balance")), IAAction("READ"))

  def postBalance(grn: GuaranteeReferenceNumber): Action[RouterBalanceRequest] =
    auth(endpointPermission).async[RouterBalanceRequest](parse.json[RouterBalanceRequest]) {
      implicit request =>
        (for {
          country         <- countryExtractionService.extractCountry(grn).asPresentation
          _               <- accessCodeService.ensureAccessCodeValid(grn, request.body.accessCode, country).asPresentation
          balanceResponse <- balanceRetrievalService.getBalance(grn, request.body.accessCode, country).asPresentation
        } yield balanceResponse).fold(
          presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError)),
          balanceResponse => Ok(Json.toJson(responses.RouterBalanceResponse(balanceResponse.balance, balanceResponse.currencyCL)))
        )
    }
}
