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
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.BalanceRetrievalError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.BalanceResponse
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[BalanceRetrievalServiceImpl])
trait BalanceRetrievalService {

  def getBalance(grn: GuaranteeReferenceNumber, accessCode: AccessCode, countryCode: CountryCode)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, BalanceRetrievalError, BalanceResponse]
}

class BalanceRetrievalServiceImpl @Inject() (connectorProvider: EISConnectorProvider) extends BalanceRetrievalService {

  override def getBalance(grn: GuaranteeReferenceNumber, accessCode: AccessCode, countryCode: CountryCode)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, BalanceRetrievalError, BalanceResponse] =
    connectorProvider(countryCode).getBalanceRequest(grn, hc).leftMap(handleConnectorError)

  // we know that we can't get all the connector errors so we don't look for them (@unchecked)
  private def handleConnectorError(error: ConnectorError): BalanceRetrievalError = (error: @unchecked) match {
    case ConnectorError.Unexpected(message, err) => BalanceRetrievalError.Unexpected(message, err)
    case ConnectorError.FailedToDeserialise      => BalanceRetrievalError.FailedToDeserialise
    case ConnectorError.GrnNotFound              => BalanceRetrievalError.GrnNotFound
  }

}
