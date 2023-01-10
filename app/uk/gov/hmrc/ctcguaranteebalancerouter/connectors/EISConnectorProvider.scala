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

package uk.gov.hmrc.ctcguaranteebalancerouter.connectors

import com.google.inject.ImplementedBy
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode

@ImplementedBy(classOf[EISConnectorProviderImpl])
trait EISConnectorProvider {
  protected def gb: EISConnector

  protected def xi: EISConnector

  def apply(countryCode: CountryCode): EISConnector = countryCode match {
    case CountryCode.Gb => gb
    case CountryCode.Xi => xi
  }
}

class EISConnectorProviderImpl extends EISConnectorProvider {
  protected lazy val gb: EISConnector = new EISConnectorImpl
  protected lazy val xi: EISConnector = new EISConnectorImpl
}
