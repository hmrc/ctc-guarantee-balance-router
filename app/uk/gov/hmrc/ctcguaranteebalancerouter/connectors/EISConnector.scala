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
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import retry.RetryDetails
import uk.gov.hmrc.ctcguaranteebalancerouter.config.CircuitBreakerConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISInstanceConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.RetryConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.metrics.HasMetrics
import uk.gov.hmrc.ctcguaranteebalancerouter.metrics.MetricsKeys
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCodeResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.BalanceResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GrnBasedRequest
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

trait EISConnector {

  def postAccessCodeRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, AccessCodeResponse]

  def postBalanceRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit ec: ExecutionContext): EitherT[Future, ConnectorError, BalanceResponse]

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
  ): EitherT[Future, ConnectorError, AccessCodeResponse] =
    post(hc, MetricsKeys.eisAccessCodeEndpoint) {
      createRequest(grn, eisInstanceConfig.accessCodeUrl)
    }

  override def postBalanceRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, BalanceResponse] =
    post(hc, MetricsKeys.eisGetBalanceEndpoint) {
      createRequest(grn, eisInstanceConfig.balanceUrl)
    }

  private def createRequest(grn: GuaranteeReferenceNumber, uri: String)(hc: HeaderCarrier)(implicit ec: ExecutionContext): RequestBuilder = {
    val request = Json.toJson(GrnBasedRequest(grn))
    httpClientV2
      .post(url"$uri")(hc)
      .withBody(request)
      .setHeader(hc.headersForUrl(headerCarrierConfig)(uri): _*)
  }

  private def post[A](hc: HeaderCarrier, metricsKey: String)(
    call: HeaderCarrier => RequestBuilder
  )(implicit ec: ExecutionContext, reads: Reads[A]): EitherT[Future, ConnectorError, A] =
    EitherT {
      protect(isFailure[A], isFailure[A], retryLogging) {
        val correlationId = UUID.randomUUID().toString
        val requestId     = hc.requestId.getOrElse("unknown")
        val requestHeaders = hc.headers(Seq(HMRCHeaderNames.xRequestId)) ++ Seq(
          "X-Correlation-Id"        -> correlationId,
          "CustomProcessHost"       -> "Digital",
          HeaderNames.ACCEPT        -> MimeTypes.JSON,
          HeaderNames.AUTHORIZATION -> s"Bearer ${eisInstanceConfig.headers.bearerToken}"
        )

        implicit val headerCarrier: HeaderCarrier = hc
          .copy(authorization = None, otherHeaders = Seq.empty)
          .withExtraHeaders(requestHeaders: _*)

        withMetricsTimerResponse(metricsKey) {
          call(headerCarrier)
            .execute[Either[UpstreamErrorResponse, HttpResponse]]
            .map[Either[ConnectorError, A]] {
              case Right(httpResponse) =>
                httpResponse.json.validate[A] match {
                  case JsSuccess(value, _) => Right(value)
                  case JsError(_) =>
                    logger.error(
                      s"Request Error: Routing to $code succeeded, but returned payload was malformed. Request ID: $requestId. Correlation ID: $correlationId."
                    )
                    Left(ConnectorError.FailedToDeserialise)
                }
              // TODO: Improve once we know what EIS will do
              case Left(UpstreamErrorResponse(_, statusCode, _, _)) if statusCode == BAD_REQUEST || statusCode == NOT_FOUND =>
                Left(ConnectorError.NotFound)
              case Left(upstreamErrorResponse) =>
                logger.error(
                  s"Request Error: Routing to $code failed to retrieve data with status code ${upstreamErrorResponse.statusCode} and message ${upstreamErrorResponse.message}. Request ID: $requestId. Correlation ID: $correlationId"
                )
                Left(ConnectorError.Upstream(upstreamErrorResponse))
            }
            .recover {
              case NonFatal(e) =>
                logger.error(
                  s"Request Error: Routing to $code failed to retrieve data with message ${e.getMessage}. Request ID: $requestId. Correlation ID: $correlationId."
                )
                Left(ConnectorError.Unexpected("message", Some(e)))
            }
        }
      }
    }

  private def isFailure[A](either: Either[ConnectorError, A]): Boolean = either match {
    case Right(_)                                                                                       => false
    case Left(ConnectorError.Upstream(UpstreamErrorResponse(_, statusCode, _, _))) if statusCode <= 499 => false
    case _                                                                                              => true
  }

  def retryLogging(response: Either[ConnectorError, _], retryDetails: RetryDetails): Unit =
    response match {
      case Left(ConnectorError.Upstream(upstreamErrorResponse)) =>
        logAttemptedRetry(s"with status code ${upstreamErrorResponse.statusCode}", retryDetails)
      case Left(ConnectorError.Unexpected(message, _)) => logAttemptedRetry(s"with error $message", retryDetails)
      case _                                           => ()
    }

  // Visibility for testing
  protected def logAttemptedRetry(message: String, retryDetails: RetryDetails): Unit = {
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
