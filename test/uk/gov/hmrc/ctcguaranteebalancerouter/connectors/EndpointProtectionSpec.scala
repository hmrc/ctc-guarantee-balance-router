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

import org.apache.pekko.stream.Materializer
import org.mockito.Mockito.verify
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.LoggerLike
import play.api.Logging
import uk.gov.hmrc.ctcguaranteebalancerouter.config.CircuitBreakerConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.RetryConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.Generators
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.TestActorSystem

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Try

class EndpointProtectionSpec
    extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with ScalaCheckDrivenPropertyChecks
    with ScalaFutures
    with Generators
    with TestActorSystem {

  object Harness extends EndpointProtection with Logging {
    override def materializer: Materializer = Materializer(TestActorSystem.system)

    override def code: String = "TEST"

    override def retries: Retries = new RetriesImpl

    override def retryConfig: RetryConfig = RetryConfig(
      3,
      100.milliseconds,
      2.seconds
    )

    override def circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(
      3,
      500.milliseconds,
      2.seconds,
      30.seconds,
      1.0,
      1.0
    )
  }

  "EndpointProtection#wrapCircuitBreak" - {

    "must return true (cause a strike) when the Try is a failure" in {

      Harness.wrapCircuitBreak[Boolean](
        _ => false
      )(Failure(new IllegalStateException())) mustBe true

    }

    "must return true (cause a strike) when the result of the strike function is true" in {

      Harness.wrapCircuitBreak[Boolean](
        r => r
      )(Try(true)) mustBe true

    }

    "must return false (not cause a strike) when the result of the strike function is false" in {

      Harness.wrapCircuitBreak[Boolean](
        r => r
      )(Try(false)) mustBe false

    }

  }

  "EndpointProtection#wrapRetryCondition" - {

    "must return a successful Future with false when retryWhen returns true (sending Future(false) to wasSuccessful)" in
      whenReady(
        Harness.wrapRetryCondition[Boolean](
          r => r
        )(true)
      ) {
        r => r mustBe false
      }

    "must return a successful Future with true when retryWhen returns false (sending Future(true) to wasSuccessful)" in
      whenReady(
        Harness.wrapRetryCondition[Boolean](
          r => r
        )(false)
      ) {
        r => r mustBe true
      }

  }

}
