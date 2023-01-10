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

import org.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.uils.Generators

class EISConnectorProviderSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaCheckDrivenPropertyChecks with ScalaFutures with Generators {

  object Harness extends EISConnectorProvider {
    override val gb: EISConnector = mock[EISConnector]

    override val xi: EISConnector = mock[EISConnector]
  }

  "EISConnectorProvider#apply" - {
    "retrieves the GB connector for country code Gb" in {
      Harness(CountryCode.Gb) mustBe Harness.gb
    }

    "retrieves the XI connector for country code Xi" in {
      Harness(CountryCode.Xi) mustBe Harness.xi
    }
  }

}
