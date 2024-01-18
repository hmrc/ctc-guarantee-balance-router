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
import cats.data.EitherT
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.FORBIDDEN
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
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.requests
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.AccessCodeResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.BalanceResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.EISResponse
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.matching.Regex

trait EISConnector {

  def postAccessCodeRequest(grn: GuaranteeReferenceNumber, accessCode: AccessCode, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, AccessCodeResponse]

  def getBalanceRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, BalanceResponse]

}

object EISConnectorImpl {
  private lazy val invalidGrnPattern: Regex = ".*Guarantee not found for GRN.*".r
}

class EISConnectorImpl(
  val code: String,
  eisInstanceConfig: EISInstanceConfig,
  headerCarrierConfig: HeaderCarrier.Config,
  httpClientV2: HttpClientV2,
  val retries: Retries,
  val metrics: Metrics,
  val clock: Clock
)(implicit val materializer: Materializer)
    extends EISConnector
    with EndpointProtection
    with Logging
    with HasMetrics {

  override val retryConfig: RetryConfig = eisInstanceConfig.retryConfig

  override val circuitBreakerConfig: CircuitBreakerConfig = eisInstanceConfig.circuitBreaker

  private val HTTP_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH).withZone(ZoneOffset.UTC)

  override def postAccessCodeRequest(grn: GuaranteeReferenceNumber, accessCode: AccessCode, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, AccessCodeResponse] = {
    val url = eisInstanceConfig.eisAccessCodeUrl.replace("{grn}", grn.value)

    post(hc, MetricsKeys.eisAccessCodeEndpoint) {
      headerCarrier =>
        val body = Json.toJson(requests.AccessCodeRequest(accessCode))
        httpClientV2
          .post(url"$url")(headerCarrier)
          .withBody(body)
          .setHeader(headerCarrier.headersForUrl(headerCarrierConfig)(url): _*)
    }
  }

  override def getBalanceRequest(grn: GuaranteeReferenceNumber, hc: HeaderCarrier)(implicit
    ec: ExecutionContext
  ): EitherT[Future, ConnectorError, BalanceResponse] = {
    val url = eisInstanceConfig.eisBalanceUrl.replace("{grn}", grn.value)
    post(hc, MetricsKeys.eisGetBalanceEndpoint) {
      headerCarrier =>
        httpClientV2
          .get(url"$url")(headerCarrier)
          .setHeader(headerCarrier.headersForUrl(headerCarrierConfig)(url): _*)
    }
  }

  private def post[A](hc: HeaderCarrier, metricsKey: String)(
    call: HeaderCarrier => RequestBuilder
  )(implicit ec: ExecutionContext, reads: Reads[A]): EitherT[Future, ConnectorError, A] =
    EitherT {
      protect(isFailure[A], isFailure[A], retryLogging) {
        val correlationId = UUID.randomUUID().toString
        val requestId     = hc.requestId.getOrElse("unknown")
        val requestHeaders = hc.headers(Seq(HMRCHeaderNames.xRequestId)) ++ Seq(
          "Date"                    -> s"${HTTP_DATE_FORMATTER.format(OffsetDateTime.now(clock))} UTC",
          "X-Correlation-Id"        -> correlationId,
          "CustomProcessHost"       -> "Digital",
          HeaderNames.ACCEPT        -> MimeTypes.JSON,
          HeaderNames.CONTENT_TYPE  -> MimeTypes.JSON,
          HeaderNames.AUTHORIZATION -> s"Bearer ${eisInstanceConfig.headers.bearerToken}"
        )

        implicit val headerCarrier: HeaderCarrier = hc
          .copy(authorization = None, otherHeaders = Seq.empty)
          .withExtraHeaders(requestHeaders: _*)

        withMetricsTimerResponse(metricsKey) {
          call(headerCarrier)
            .execute[HttpResponse]
            .map[Either[ConnectorError, A]] {
              response: HttpResponse =>
                response.status match {
                  case FORBIDDEN =>
                    logger.error(
                      s"Request Error: Routing to $code failed to retrieve data with status code ${response.status} and message ${response.body}. Request ID: $requestId. Correlation ID: $correlationId"
                    )

                    response.json
                      .validate[EISResponse]
                      .asOpt
                      .map(
                        r => Left(deriveErrorFromResponseMessage(r))
                      )
                      .getOrElse(Left(ConnectorError.Unexpected("Failed to deserialize error response from EIS", None)))

                  case success if success >= 200 & success < 300 =>
                    response.json.validate[A] match {
                      case JsSuccess(value, _) => Right(value)
                      case JsError(_) =>
                        logger.error(
                          s"Request Error: Routing to $code succeeded, but returned payload was malformed. Request ID: $requestId. Correlation ID: $correlationId."
                        )
                        Left(ConnectorError.FailedToDeserialise)
                    }
                  case _ =>
                    logger.error(
                      s"Request Error: Routing to $code failed to retrieve data with status code ${response.status} and message ${response.body}. Request ID: $requestId. Correlation ID: $correlationId"
                    )
                    Left(ConnectorError.Unexpected("Unexpected response from EIS", None))
                }
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

  private def deriveErrorFromResponseMessage(response: EISResponse): ConnectorError =
    response.message match {
      case "Not Valid Access Code for this operation"    => ConnectorError.InvalidAccessCode
      case "Not Valid Guarantee Type for this operation" => ConnectorError.InvalidGuaranteeType
      case EISConnectorImpl.invalidGrnPattern()          => ConnectorError.GrnNotFound
      case _                                             => ConnectorError.Unexpected("Unexpected response from EIS", None)
    }

  private def isFailure[A](either: Either[ConnectorError, A]): Boolean = either match {
    case Right(_)                           => false
    case Left(_: ConnectorError.Unexpected) => true
    case Left(_)                            => false
  }

  def retryLogging(response: Either[ConnectorError, _], retryDetails: RetryDetails): Unit =
    response match {
      case Left(ConnectorError.GrnNotFound) =>
        logAttemptedRetry(s"with status code 404", retryDetails)
      case Left(ConnectorError.InvalidAccessCode) =>
        logAttemptedRetry(s"with status code 403", retryDetails)
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
