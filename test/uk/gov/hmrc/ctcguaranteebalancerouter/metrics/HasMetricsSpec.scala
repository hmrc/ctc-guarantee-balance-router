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

package uk.gov.hmrc.ctcguaranteebalancerouter.metrics

import com.codahale.metrics.Counter
import com.codahale.metrics.Histogram
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.Timer
import com.kenshoo.play.metrics.Metrics
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.compatible.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError

import scala.concurrent.Future

class HasMetricsSpec extends AsyncWordSpecLike with Matchers with OptionValues with MockitoSugar with BeforeAndAfterAll {

  trait MockHasMetrics {
    self: HasMetrics =>
    val timerContext                           = mock[Timer.Context]
    val timer                                  = mock[Timer]
    val successCounter                         = mock[Counter]
    val failureCounter                         = mock[Counter]
    val histogram                              = mock[Histogram]
    val metrics                                = mock[Metrics]
    override lazy val registry: MetricRegistry = mock[MetricRegistry]
    when(registry.timer(anyString())) thenReturn timer
    when(registry.counter(endsWith("success-counter"))) thenReturn successCounter
    when(registry.counter(endsWith("failed-counter"))) thenReturn failureCounter
    when(registry.histogram(anyString())) thenReturn histogram
    when(timer.time()) thenReturn timerContext
    when(timerContext.stop()) thenReturn 0L
  }

  class TestHasMetrics extends HasMetrics with MockHasMetrics

  def withTestMetrics[A](test: TestHasMetrics => A): A =
    test(new TestHasMetrics)

  def verifyCompletedWithSuccess(metricName: String, metrics: MockHasMetrics): Assertion = {
    val inOrder = Mockito.inOrder(metrics.timer, metrics.timerContext, metrics.successCounter)
    inOrder.verify(metrics.timer, times(1)).time()
    inOrder.verify(metrics.timerContext, times(1)).stop()
    inOrder.verify(metrics.successCounter, times(1)).inc()
    verifyNoMoreInteractions(metrics.timer)
    verifyNoMoreInteractions(metrics.timerContext)
    verifyNoMoreInteractions(metrics.successCounter)
    verifyNoInteractions(metrics.failureCounter)
    succeed
  }

  def verifyCompletedWithFailure(metricName: String, metrics: MockHasMetrics): Assertion = {
    val inOrder = Mockito.inOrder(metrics.timer, metrics.timerContext, metrics.failureCounter)
    inOrder.verify(metrics.timer, times(1)).time()
    inOrder.verify(metrics.timerContext, times(1)).stop()
    inOrder.verify(metrics.failureCounter, times(1)).inc()
    verifyNoMoreInteractions(metrics.timer)
    verifyNoMoreInteractions(metrics.timerContext)
    verifyNoMoreInteractions(metrics.failureCounter)
    verifyNoInteractions(metrics.successCounter)
    succeed
  }

  val TestMetric = "test-metric"

  "HasMetrics" when {
    "withMetricsTimerResponse" should {
      "increment success counter for a successful future with a Right" in withTestMetrics {
        metrics =>
          metrics
            .withMetricsTimerResponse(TestMetric)(
              Future.successful(Right(()))
            )
            .map {
              _ =>
                verifyCompletedWithSuccess(TestMetric, metrics)
            }
      }

      "increment failure counter for a successful future with a Left" in withTestMetrics {
        metrics =>
          metrics
            .withMetricsTimerResponse(TestMetric)(
              Future.successful(Left(ConnectorError.Unexpected("oops", None)))
            )
            .map {
              _: Any =>
                verifyCompletedWithSuccess(TestMetric, metrics)
            }
      }

      "increment failure counter for a failed future" in withTestMetrics {
        metrics =>
          metrics
            .withMetricsTimerResponse[Unit](TestMetric)(
              Future.failed(new Exception)
            )
            .map {
              // Here to ensure the future is converted to the correct type
              _ => fail("This should not succeed")
            }
            .recover {
              case _ =>
                verifyCompletedWithFailure(TestMetric, metrics)
            }
      }

      "increment failure counter when the user throws an exception constructing their code block" in withTestMetrics {
        metrics =>
          metrics.withMetricsTimerResponse[Any](TestMetric) {
            Future.successful(Left(ConnectorError.Unexpected("oops", Some(new RuntimeException))))
          }

          Future.successful(verifyCompletedWithFailure(TestMetric, metrics))
      }

      "increment failure counter an exception is thrown" in withTestMetrics {
        metrics =>
          assertThrows[RuntimeException] {
            metrics.withMetricsTimerResponse[Any](TestMetric) {
              throw new RuntimeException()
            }

            Future.successful(verifyCompletedWithFailure(TestMetric, metrics))
          }
      }
    }
  }

}
