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

import org.scalatest.freespec.AnyFreeSpec
import play.api.inject.guice.GuiceApplicationBuilder

class AppConfigSpec extends AnyFreeSpec {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()

  "configuration can be loaded" in {
    val config = appBuilder.build().injector.instanceOf[AppConfig]
    // The following force the config to be loaded
    config.eisGbConfig
    config.eisXiConfig
    config.internalAuthEnabled

    // we're just checking it can be loaded here.
    succeed
  }

}
