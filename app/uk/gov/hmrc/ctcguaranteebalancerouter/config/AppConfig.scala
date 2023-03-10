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

package uk.gov.hmrc.ctcguaranteebalancerouter.config

import javax.inject.Inject
import javax.inject.Singleton
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class AppConfig @Inject() (config: Configuration) {

  lazy val appName: String                           = config.get[String]("appName")
  lazy val headerCarrierConfig: HeaderCarrier.Config = HeaderCarrier.Config.fromConfig(config.underlying)

  lazy val eisGbConfig: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.gb")
  lazy val eisXiConfig: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.xi")

  lazy val internalAuthEnabled: Boolean = config.get[Boolean]("microservice.services.internal-auth.enabled")
}
