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

import com.codahale.metrics.MetricRegistry
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

trait HasMetrics {
  def metrics: Metrics

  lazy val registry: MetricRegistry = metrics.defaultRegistry

  class MetricsTimer(metricKey: String) {
    val timerContext   = registry.timer(s"$metricKey-timer").time()
    val successCounter = registry.counter(s"$metricKey-success-counter")
    val failureCounter = registry.counter(s"$metricKey-failed-counter")
    val timerRunning   = new AtomicBoolean(true)

    def completeWithSuccess(): Unit =
      if (timerRunning.compareAndSet(true, false)) {
        timerContext.stop()
        successCounter.inc()
      }

    def completeWithFailure(): Unit =
      if (timerRunning.compareAndSet(true, false)) {
        timerContext.stop()
        failureCounter.inc()
      }
  }

  /** Execute a block of code with a metrics timer. Intended for use in controllers that return HTTP responses.
    *
    * @param metric
    *   The id of the metric to be collected
    * @param block
    *   The block of code to execute asynchronously
    * @param ec
    *   The [[scala.concurrent.ExecutionContext]] on which the block of code should run
    * @return
    *   The result of the block of code
    */
  def withMetricsTimerResponse[A](
    metricKey: String
  )(block: => Future[Either[ConnectorError, A]])(implicit ec: ExecutionContext): Future[Either[ConnectorError, A]] =
    withMetricsTimer(metricKey) {
      timer =>
        val response = block

        // Clean up timer according to server response
        response.foreach {
          case Right(_) => timer.completeWithSuccess()
          case Left(_)  => timer.completeWithFailure()
        }

        // Clean up timer for unhandled exceptions
        response.failed.foreach(
          _ => timer.completeWithFailure()
        )

        response
    }

  def withMetricsTimer[T](metricKey: String)(block: MetricsTimer => T): T = {
    val timer = new MetricsTimer(metricKey)

    try block(timer)
    catch {
      case NonFatal(e) =>
        timer.completeWithFailure()
        throw e
    }
  }

}
