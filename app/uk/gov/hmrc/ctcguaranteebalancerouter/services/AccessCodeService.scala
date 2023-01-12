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

package uk.gov.hmrc.ctcguaranteebalancerouter.services

import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import uk.gov.hmrc.ctcguaranteebalancerouter.connectors.EISConnectorProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCodeResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.RoutingError
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[AccessCodeServiceImpl])
trait AccessCodeService {

  def ensureAccessCodeValid(grn: GuaranteeReferenceNumber, accessCode: AccessCode, countryCode: CountryCode)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, AccessCodeError, Unit]
}

class AccessCodeServiceImpl @Inject() (connectorProvider: EISConnectorProvider) extends AccessCodeService {

  override def ensureAccessCodeValid(grn: GuaranteeReferenceNumber, accessCode: AccessCode, countryCode: CountryCode)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, AccessCodeError, Unit] =
    for {
      result          <- connectorProvider(countryCode).postAccessCodeRequest(grn, hc).leftMap(handleRoutingError)
      validAccessCode <- extractAccessCode(result)
      _               <- validateCode(accessCode, validAccessCode)
    } yield ()

  private def extractAccessCode(value: JsValue)(implicit ec: ExecutionContext): EitherT[Future, AccessCodeError, AccessCode] =
    value.validate[AccessCodeResponse] match {
      case JsSuccess(value, _) => EitherT.rightT(value.accessCode)
      case JsError(_)          => EitherT.leftT(AccessCodeError.InvalidJson)
    }

  private def validateCode(providedCode: AccessCode, expectedCode: AccessCode)(implicit ec: ExecutionContext): EitherT[Future, AccessCodeError, Unit] =
    if (providedCode == expectedCode) EitherT.rightT(())
    else EitherT.leftT(AccessCodeError.InvalidAccessCode)

  // EIS will only return select status codes, so we need to infer from there
  private def handleRoutingError(error: RoutingError): AccessCodeError = error match {
    case RoutingError.Upstream(UpstreamErrorResponse(_, statusCode, _, _)) if statusCode <= 499 => AccessCodeError.NotFound
    case x                                                                                      => AccessCodeError.Routing(x)
  }

}
