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
import cats.data.EitherT
import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import retry.RetryDetails
import uk.gov.hmrc.ctcguaranteebalancerouter.config.CircuitBreakerConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISInstanceConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.RetryConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.metrics.HasMetrics
import uk.gov.hmrc.ctcguaranteebalancerouter.metrics.MetricsKeys
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCodeRequest
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.RoutingError
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

trait EISConnector {

  def postAccessCodeRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit ec: ExecutionContext): EitherT[Future, RoutingError, JsValue]

}

class EISConnectorImpl(
  val code: String,
  eisInstanceConfig: EISInstanceConfig,
  headerCarrierConfig: HeaderCarrier.Config,
  httpClientV2: HttpClientV2,
  val retries: Retries,
  val metrics: Metrics
)(implicit val materializer: Materializer)
    extends EISConnector
    with EndpointProtection
    with Logging
    with HasMetrics {

  override val retryConfig: RetryConfig = eisInstanceConfig.retryConfig

  override val circuitBreakerConfig: CircuitBreakerConfig = eisInstanceConfig.circuitBreaker

  override def postAccessCodeRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, RoutingError, JsValue] = {
    val request = Json.toJson(AccessCodeRequest(grn))
    EitherT {
      protect(isFailure, isFailure, retryLogging) {
        val correlationId = UUID.randomUUID().toString;
        val requestHeaders = hc.headers(Seq(HMRCHeaderNames.xRequestId)) ++ Seq(
          "X-Correlation-Id"        -> correlationId,
          "CustomProcessHost"       -> "Digital",
          HeaderNames.ACCEPT        -> MimeTypes.JSON,
          HeaderNames.AUTHORIZATION -> s"Bearer ${eisInstanceConfig.headers.bearerToken}"
        )

        implicit val headerCarrier: HeaderCarrier = hc
          .copy(authorization = None, otherHeaders = Seq.empty)
          .withExtraHeaders(requestHeaders: _*)

        withMetricsTimerResponse(MetricsKeys.eisAccessCodeEndpoint) {
          httpClientV2
            .post(url"${eisInstanceConfig.accessCodeUrl}")
            .withBody(request)
            .setHeader(headerCarrier.headersForUrl(headerCarrierConfig)(eisInstanceConfig.accessCodeUrl): _*)
            .execute[Either[UpstreamErrorResponse, HttpResponse]]
            .map {
              case Right(httpResponse)         => Right(httpResponse.json)
              case Left(upstreamErrorResponse) => Left(RoutingError.Upstream(upstreamErrorResponse))
            }
            .recover {
              case NonFatal(e) =>
                logger.error(s"Request Error: Routing to $code failed to retrieve data with message ${e.getMessage}. Correlation ID: $correlationId.")
                Left[RoutingError, JsValue](RoutingError.Unexpected("message", Some(e)))
            }
        }
      }
    }
  }

  private val isFailure: PartialFunction[Either[RoutingError, JsValue], Boolean] = {
    case Right(_)                                                                                     => false
    case Left(RoutingError.Upstream(UpstreamErrorResponse(_, statusCode, _, _))) if statusCode <= 499 => false
    case _                                                                                            => true
  }

  def retryLogging(response: Either[RoutingError, _], retryDetails: RetryDetails): Unit =
    response match {
      case Left(RoutingError.Upstream(upstreamErrorResponse)) =>
        logAttemptedRetry(s"with status code ${upstreamErrorResponse.statusCode}", retryDetails)
      case Left(RoutingError.Unexpected(message, _)) => logAttemptedRetry(s"with error $message", retryDetails)
      case _                                         => ()
    }

  private def logAttemptedRetry(message: String, retryDetails: RetryDetails): Unit = {
    val attemptNumber = retryDetails.retriesSoFar + 1
    if (retryDetails.givingUp) {
      logger.error(
        s"Message when routing to $code failed $message\n" +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds, giving up."
      )
    } else {
      val nextAttempt =
        retryDetails.upcomingDelay
          .map(
            d => s"in ${d.toSeconds} seconds"
          )
          .getOrElse("immediately")
      logger.warn(
        s"Message when routing to $code failed with $message\n" +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds so far, trying again $nextAttempt."
      )
    }
  }
}
