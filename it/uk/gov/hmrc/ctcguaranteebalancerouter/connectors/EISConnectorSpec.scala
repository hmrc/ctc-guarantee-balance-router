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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.mockito.ArgumentMatchers
import org.mockito.MockitoSugar
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.test.Helpers._
import retry.RetryDetails
import retry.RetryPolicies
import retry.RetryPolicy
import uk.gov.hmrc.ctcguaranteebalancerouter.config.CircuitBreakerConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISInstanceConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISURIsConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.Headers
import uk.gov.hmrc.ctcguaranteebalancerouter.config.RetryConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.Generators
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.RegexPatterns
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestActorSystem
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestHelpers
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestMetrics
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCodeResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.HttpClientV2Support

import java.net.URL
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EISConnectorSpec
    extends AnyWordSpec
    with HttpClientV2Support
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ScalaCheckPropertyChecks
    with TableDrivenPropertyChecks
    with TestActorSystem
    with Generators {

  private object NoRetries extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.alwaysGiveUp[Future](cats.implicits.catsStdInstancesForFuture(ec))
  }

  private object OneRetry extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.limitRetries[Future](1)(cats.implicits.catsStdInstancesForFuture(ec))
  }

  val accessCodeUri = "/ctc-guarantee-balance-eis-stub/guarantee/accessCode"

  val connectorConfig: EISInstanceConfig = EISInstanceConfig(
    "http",
    "localhost",
    wiremockPort,
    EISURIsConfig(
      accessCodeUri,
      "/ctc-guarantee-balance-eis-stub/guarantee/balance"
    ),
    Headers("bearertokenhereGB"),
    CircuitBreakerConfig(
      3,
      2.seconds,
      2.seconds,
      3.seconds,
      1,
      0
    ),
    RetryConfig(
      1,
      1.second,
      2.seconds
    )
  )

  // We construct the connector each time to avoid issues with the circuit breaker
  def noRetriesConnector = new EISConnectorImpl("NoRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics)

  def oneRetryConnector = new EISConnectorImpl("OneRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, OneRetry, new TestMetrics)

  lazy val connectorGen: Gen[() => EISConnector] = Gen.oneOf(() => noRetriesConnector, () => oneRetryConnector)

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<test></test>"))

  "postAccessCodeRequest" should {

    "add CustomProcessHost and X-Correlation-Id headers to messages for GB and return a Right when successful" in forAll(
      connectorGen,
      Arbitrary.arbitrary[GuaranteeReferenceNumber]
    ) {
      (connector, grn) =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        // Important note: while this test considers successes, as this connector has a retry function,
        // we have to ensure that any success result is not retried. To do this, we make the stub return
        // a 202 status the first time it is called, then we transition it into a state where it'll return
        // an error. As the retry algorithm should not attempt a retry on a 202, the stub should only be
        // called once - so a 500 should never be returned.
        //
        // If a 500 error is returned, this most likely means a retry happened, the first place to look
        // should be the code the determines if a result is successful.

        def stub(currentState: String, targetState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo(accessCodeUri)
            )
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
              .withHeader("CustomProcessHost", equalTo("Digital"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
              .willReturn(
                aResponse()
                  .withStatus(codeToReturn)
                  .withBody(Json.stringify(Json.obj("GRN" -> grn.value, "accessCode" -> "ABCD")))
              )
              .willSetStateTo(targetState)
          )

        val secondState = "should now fail"

        stub(Scenario.STARTED, secondState, OK)
        stub(secondState, secondState, INTERNAL_SERVER_ERROR)

        val hc = HeaderCarrier()

        whenReady(connector().postAccessCodeRequest(grn, hc).value) {
          case Right(AccessCodeResponse(grn, AccessCode("ABCD"))) => succeed
          case Right(x)                                           => fail(s"Got $x, which was not expected")
          case Left(ex)                                           => fail(s"Failed with ${ex.toString}")
        }
    }

    "return JsValue when post is successful on retry if there is an initial failure" in {
      def stub(currentState: String, targetState: String, codeToReturn: Int) =
        server.stubFor(
          post(
            urlEqualTo(accessCodeUri)
          )
            .inScenario("Flaky Call")
            .whenScenarioStateIs(currentState)
            .willSetStateTo(targetState)
            .withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .willReturn(
              aResponse()
                .withStatus(codeToReturn)
                .withBody(Json.stringify(Json.obj("GRN" -> "abc", "accessCode" -> "ABCD")))
            )
        )

      val secondState = "should now succeed"

      stub(Scenario.STARTED, secondState, INTERNAL_SERVER_ERROR)
      stub(secondState, secondState, OK)

      val hc = HeaderCarrier()

      whenReady(oneRetryConnector.postAccessCodeRequest(GuaranteeReferenceNumber("abc"), hc).value) {
        case Right(AccessCodeResponse(GuaranteeReferenceNumber("abc"), AccessCode("ABCD"))) => succeed
        case Right(x) =>
          fail(s"Got $x, which was not expected")
        case Left(ex) =>
          fail(s"Failed with ${ex.toString}")
      }
    }

    // We're making a guess that EIS won't return a 404, but a 400 due to zero trust.
    "a 400 error becomes not found" in forAll(connectorGen) {
      connector =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        server.stubFor(
          post(
            urlEqualTo(accessCodeUri)
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .willReturn(aResponse().withStatus(BAD_REQUEST))
        )

        val hc = HeaderCarrier()

        whenReady(connector().postAccessCodeRequest(GuaranteeReferenceNumber("abc"), hc).value) {
          case Left(ConnectorError.NotFound) => succeed
          case x                             => fail(s"Left was not a RoutingError.NotFound (got $x)")
        }
    }

    Seq(
      INTERNAL_SERVER_ERROR,
      BAD_GATEWAY,
      GATEWAY_TIMEOUT
    ).foreach {
      statusCode =>
        s"pass through error status code $statusCode" in forAll(connectorGen) {
          connector =>
            server.resetAll() // Need to reset due to the forAll - it's technically the same test

            server.stubFor(
              post(
                urlEqualTo(accessCodeUri)
              ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
                .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
                .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
                .willReturn(aResponse().withStatus(statusCode))
            )

            val hc = HeaderCarrier()

            whenReady(connector().postAccessCodeRequest(GuaranteeReferenceNumber("abc"), hc).value) {
              case Left(x: ConnectorError.Upstream) =>
                x.upstreamErrorResponse.statusCode mustBe statusCode
              case x =>
                fail(s"Left was not a RoutingError.Upstream (got $x)")
            }
        }
    }

    "handle exceptions by returning an HttpResponse with status code 500" in {
      val httpClientV2 = mock[HttpClientV2]

      val hc        = HeaderCarrier()
      val connector = new EISConnectorImpl("Failure", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics)

      when(httpClientV2.post(ArgumentMatchers.any[URL])(ArgumentMatchers.any[HeaderCarrier])).thenReturn(new FakeRequestBuilder)

      whenReady(connector.postAccessCodeRequest(GuaranteeReferenceNumber("abc"), hc).value) {
        case Left(x) if x.isInstanceOf[ConnectorError.Unexpected] => x.asInstanceOf[ConnectorError.Unexpected].cause.get mustBe a[RuntimeException]
        case _                                                    => fail("Left was not a RoutingError.Unexpected")
      }
    }
  }

  "retryLogging" should {

    val testCases: Seq[(String, Either[ConnectorError, HttpResponse])] = Seq(
      "Unexpected Error" -> Left(ConnectorError.Unexpected("bleh", None)),
      "Upstream Error"   -> Left(ConnectorError.Upstream(UpstreamErrorResponse("bleh", INTERNAL_SERVER_ERROR))),
      "Success"          -> Right(mock[HttpResponse])
    )

    testCases foreach {
      entry =>
        s"always return unit for ${entry._1}" in {
          noRetriesConnector.retryLogging(entry._2, RetryDetails.GivingUp(1, 20.seconds))
          succeed // i.e. an exception wasn't thrown
        }
    }
  }

}
