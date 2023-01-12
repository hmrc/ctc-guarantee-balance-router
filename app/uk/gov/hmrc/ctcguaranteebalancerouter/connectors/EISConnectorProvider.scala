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

import akka.stream.Materializer
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.ctcguaranteebalancerouter.config.AppConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.http.client.HttpClientV2

@ImplementedBy(classOf[EISConnectorProviderImpl])
trait EISConnectorProvider {
  protected def gb: EISConnector

  protected def xi: EISConnector

  def apply(countryCode: CountryCode): EISConnector = countryCode match {
    case CountryCode.Gb => gb
    case CountryCode.Xi => xi
  }
}

class EISConnectorProviderImpl @Inject() (appConfig: AppConfig, httpClientV2: HttpClientV2, retries: Retries, metrics: Metrics)(implicit
  materializer: Materializer
) extends EISConnectorProvider {
  protected lazy val gb: EISConnector = new EISConnectorImpl("GB", appConfig.eisGbConfig, appConfig.headerCarrierConfig, httpClientV2, retries, metrics)
  protected lazy val xi: EISConnector = new EISConnectorImpl("XI", appConfig.eisXiConfig, appConfig.headerCarrierConfig, httpClientV2, retries, metrics)
}
