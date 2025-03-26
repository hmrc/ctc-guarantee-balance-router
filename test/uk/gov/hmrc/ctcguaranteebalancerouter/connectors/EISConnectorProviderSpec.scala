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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.ctcguaranteebalancerouter.config.AppConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISInstanceConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.http.client.HttpClientV2

import java.time.Clock
import org.mockito.Mockito.when
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

class EISConnectorProviderImplSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaFutures {

  implicit val system: ActorSystem        = ActorSystem("TestSystem")
  implicit val materializer: Materializer = Materializer(system)

  "EISConnectorProviderImpl" - {
    "should return the same connector instance on multiple invocations and distinct connectors for different country codes" in {
      val appConfig     = mock[AppConfig]
      val dummyGbConfig = mock[EISInstanceConfig]
      val dummyXiConfig = mock[EISInstanceConfig]
      when(appConfig.eisGbConfig).thenReturn(dummyGbConfig)
      when(appConfig.eisXiConfig).thenReturn(dummyXiConfig)

      when(appConfig.eisGbConfig).thenReturn(dummyGbConfig)
      when(appConfig.eisXiConfig).thenReturn(dummyXiConfig)

      val httpClientV2 = mock[HttpClientV2]
      val retries      = mock[Retries]
      val metrics      = mock[Metrics]
      val clock        = Clock.systemUTC()

      val provider = new EISConnectorProviderImpl(appConfig, httpClientV2, retries, metrics, clock)

      val gbConnector1 = provider(CountryCode.Gb)
      val gbConnector2 = provider(CountryCode.Gb)
      gbConnector1 must be theSameInstanceAs gbConnector2
      val xiConnector1 = provider(CountryCode.Xi)
      val xiConnector2 = provider(CountryCode.Xi)
      xiConnector1 must be theSameInstanceAs xiConnector2
      gbConnector1 must not be theSameInstanceAs(xiConnector1)

    }
  }
}
