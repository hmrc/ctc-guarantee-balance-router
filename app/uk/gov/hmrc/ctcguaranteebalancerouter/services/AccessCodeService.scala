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
import uk.gov.hmrc.ctcguaranteebalancerouter.connectors.EISConnectorProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.http.HeaderCarrier

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
      _ <- connectorProvider(countryCode).postAccessCodeRequest(grn, accessCode, hc).leftMap(handleRoutingError)
    } yield ()

  // EIS will only return select status codes, so we need to infer from there
  private def handleRoutingError(error: ConnectorError): AccessCodeError = error match {
    case ConnectorError.InvalidAccessCode          => AccessCodeError.InvalidAccessCode
    case ConnectorError.GrnNotFound                => AccessCodeError.GrnNotFound
    case ConnectorError.FailedToDeserialise        => AccessCodeError.FailedToDeserialise
    case ConnectorError.Unexpected(message, error) => AccessCodeError.Unexpected(message, error)
  }

}
