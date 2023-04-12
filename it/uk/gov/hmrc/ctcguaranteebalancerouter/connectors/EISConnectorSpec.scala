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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockitoSugar
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Logger
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.test.Helpers._
import retry.RetryDetails
import retry.RetryPolicies
import retry.RetryPolicy
import uk.gov.hmrc.ctcguaranteebalancerouter.config.CircuitBreakerConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.EISInstanceConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.config.Headers
import uk.gov.hmrc.ctcguaranteebalancerouter.config.RetryConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.Generators
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.RegexPatterns
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestActorSystem
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestHelpers
import uk.gov.hmrc.ctcguaranteebalancerouter.itbase.TestMetrics
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.AccessCodeResponse
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.HttpClientV2Support

import java.net.URL
import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EISConnectorSpec
    extends AnyWordSpec
    with BeforeAndAfterEach
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

  def accessCodeUri(grn: GuaranteeReferenceNumber) = s"/ctc-guarantee-balance-eis-stub/guarantees/${grn.value}/access-codes"
  def balanceUri(grn: GuaranteeReferenceNumber)    = s"/ctc-guarantee-balance-eis-stub/guarantees/${grn.value}/balance"

  val connectorConfig: EISInstanceConfig = EISInstanceConfig(
    "http",
    "localhost",
    wiremockPort,
    "/ctc-guarantee-balance-eis-stub",
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

  val loggerMock: Logger = mock[Logger]

  // We construct the connector each time to avoid issues with the circuit breaker
  def noRetriesConnector: EISConnectorImpl =
    new EISConnectorImpl("NoRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics) {
      override protected val logger: Logger = loggerMock

      override protected def logAttemptedRetry(message: String, retryDetails: RetryDetails): Unit = ()
    }

  def noRetriesConnectorWithRetryLogging: EISConnectorImpl =
    new EISConnectorImpl("NoRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics) {
      override protected val logger: Logger = loggerMock
    }

  def oneRetryConnector: EISConnectorImpl =
    new EISConnectorImpl("OneRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, OneRetry, new TestMetrics) {
      override protected val logger: Logger = loggerMock

      override protected def logAttemptedRetry(message: String, retryDetails: RetryDetails): Unit = ()
    }

  val errorTestConnector: EISConnectorImpl =
    new EISConnectorImpl("NoRetry", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics) {
      override protected val logger: Logger = loggerMock

      override protected def logAttemptedRetry(message: String, retryDetails: RetryDetails): Unit = ()

      override def protect[A](retryWhen: A => Boolean, circuitBreakWhen: A => Boolean, retryLogging: (A, RetryDetails) => Unit)(block: => Future[A])(implicit
        ec: ExecutionContext
      ): Future[A] =
        super.protect(retryWhen, (_: A) => false, retryLogging)(block)(ec) // short circuit retries
    }

  lazy val connectorGen: Gen[() => EISConnector] = Gen.oneOf(() => noRetriesConnector, () => oneRetryConnector)

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<test></test>"))

  override def afterEach(): Unit =
    reset(loggerMock)

  def accessCodeResponseBody(grn: GuaranteeReferenceNumber) =
    Json.stringify(Json.obj("grn" -> grn.value, "masterAccessCode" -> "ABCD", "additionalAccessCodes" -> Json.arr("AB34")))

  val invalidAccessCodeResponseBody =
    Json.stringify(Json.obj("message" -> "Not Valid Access Code for this operation", "timestamp" -> OffsetDateTime.now().toString, "path" -> "..."))

  def grnNotFoundResponseBody(grn: GuaranteeReferenceNumber) =
    Json.stringify(Json.obj("message" -> s"Guarantee not found for GRN: ${grn.value}", "timestamp" -> OffsetDateTime.now().toString, "path" -> "..."))

  def balanceResponseBody(grn: GuaranteeReferenceNumber) = Json.stringify(Json.obj("grn" -> grn.value, "balance" -> 123.45, "currencyCL" -> "GBP"))

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

        def stub(currentState: String, targetState: String, codeToReturn: Int, body: String) =
          server.stubFor(
            post(
              urlEqualTo(accessCodeUri(grn))
            )
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
              .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
              .withHeader("CustomProcessHost", equalTo("Digital"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
              .willReturn(
                aResponse()
                  .withStatus(codeToReturn)
                  .withBody(body)
              )
              .willSetStateTo(targetState)
          )

        val secondState = "should now fail"

        stub(Scenario.STARTED, secondState, OK, accessCodeResponseBody(grn))
        stub(secondState, secondState, INTERNAL_SERVER_ERROR, invalidAccessCodeResponseBody)

        val hc = HeaderCarrier()

        whenReady(connector().postAccessCodeRequest(grn, AccessCode("ABCD"), hc).value) {
          case Right(AccessCodeResponse(GuaranteeReferenceNumber(grn.value), AccessCode("ABCD"), List(AccessCode("AB34")))) =>
            verify(loggerMock, times(0)).error(anyString())(any())
          case Right(x) => fail(s"Got $x, which was not expected")
          case Left(ex) => fail(s"Failed with ${ex.toString}")
        }
    }

    "return an AccessCodeResponse when post is successful on retry if there is an initial failure" in {
      val grn = GuaranteeReferenceNumber("X1Y2")
      def stub(currentState: String, targetState: String, codeToReturn: Int, body: String) =
        server.stubFor(
          post(
            urlEqualTo(accessCodeUri(grn))
          )
            .inScenario("Flaky Call")
            .whenScenarioStateIs(currentState)
            .willSetStateTo(targetState)
            .withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("CustomProcessHost", equalTo("Digital"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
            .willReturn(
              aResponse()
                .withStatus(codeToReturn)
                .withBody(body)
            )
            .willSetStateTo(targetState)
        )

      val secondState = "should now succeed"

      stub(Scenario.STARTED, secondState, BAD_REQUEST, "error")
      stub(secondState, secondState, OK, accessCodeResponseBody(grn))

      val hc = HeaderCarrier()

      whenReady(oneRetryConnector.postAccessCodeRequest(grn, AccessCode("ABCD"), hc).value) {
        case Right(AccessCodeResponse(GuaranteeReferenceNumber(grn.value), AccessCode("ABCD"), List(AccessCode("AB34")))) =>
          verify(loggerMock, times(1)).error(anyString())(any())
        case Right(x) =>
          fail(s"Got $x, which was not expected")
        case Left(ex) =>
          fail(s"Failed with ${ex.toString}")
      }
    }

    // EIS always returns 500 status code if an error occurs
    // we need to check the message in the response to derive the appropriate status code
    "Invalid access code response from eis is correctly deserialised as ConnectorError.InvalidAccessCode" in forAll(
      connectorGen
    ) {
      connector =>
        afterEach()
        val grn = GuaranteeReferenceNumber("abc")
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        server.stubFor(
          post(
            urlEqualTo(accessCodeUri(grn))
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR).withBody(invalidAccessCodeResponseBody))
        )

        val hc = HeaderCarrier()

        whenReady(connector().postAccessCodeRequest(grn, AccessCode("ABCD"), hc).value) {
          case Left(ConnectorError.InvalidAccessCode) => verify(loggerMock, times(1)).error(anyString())(any())
          case x                                      => fail(s"Left was not a ConnectorError.InvalidAccessCode (got $x)")
        }
    }

    "Grn not found response from eis is correctly deserialised as ConnectorError.GrnNotFound" in forAll(
      connectorGen
    ) {
      connector =>
        afterEach()
        val grn = GuaranteeReferenceNumber("abc")
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        server.stubFor(
          post(
            urlEqualTo(accessCodeUri(grn))
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR).withBody(grnNotFoundResponseBody(grn)))
        )

        val hc = HeaderCarrier()

        whenReady(connector().postAccessCodeRequest(grn, AccessCode("ABCD"), hc).value) {
          case Left(ConnectorError.GrnNotFound) => verify(loggerMock, times(1)).error(anyString())(any())
          case x                                => fail(s"Left was not a ConnectorError.GrnNotFound (got $x)")
        }
    }

  }

  "handle exceptions by returning an HttpResponse with status code 500" in {
    val httpClientV2 = mock[HttpClientV2]

    val hc = HeaderCarrier()
    val connector = new EISConnectorImpl("Failure Test", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics) {
      override protected val logger: Logger = loggerMock

      override def retryLogging(response: Either[ConnectorError, _], retryDetails: RetryDetails): Unit = ()
    }

    when(httpClientV2.post(any[URL])(any[HeaderCarrier])).thenReturn(new FakeRequestBuilder)

    whenReady(connector.postAccessCodeRequest(GuaranteeReferenceNumber("abc"), AccessCode("ABCD"), hc).value) {
      case Left(x: ConnectorError.Unexpected) =>
        verify(loggerMock, times(1)).error(anyString())(any())
        x.cause.get mustBe a[RuntimeException]
      case _ => fail("Left was not a RoutingError.Unexpected")
    }
  }

  "getBalanceRequest" should {

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

        def stub(currentState: String, targetState: String, codeToReturn: Int, body: String) =
          server.stubFor(
            get(
              urlEqualTo(balanceUri(grn))
            )
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
              .withHeader("CustomProcessHost", equalTo("Digital"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
              .willReturn(
                aResponse()
                  .withStatus(codeToReturn)
                  .withBody(body)
              )
              .willSetStateTo(targetState)
          )

        val secondState = "should now fail"

        stub(Scenario.STARTED, secondState, OK, balanceResponseBody(grn))
        stub(secondState, secondState, INTERNAL_SERVER_ERROR, grnNotFoundResponseBody(grn))

        val hc = HeaderCarrier()

        whenReady(connector().getBalanceRequest(grn, hc).value) {
          case Right(_) => verify(loggerMock, times(0)).error(anyString())(any())
          case Left(ex) => fail(s"Failed with ${ex.toString}")
        }
    }

    "return BalanceResponse when post is successful on retry if there is an initial failure" in {
      val grn = GuaranteeReferenceNumber("abc")
      server.resetAll() // Need to reset due to the forAll - it's technically the same test

      def stub(currentState: String, targetState: String, codeToReturn: Int, body: String) =
        server.stubFor(
          get(
            urlEqualTo(balanceUri(grn))
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
                .withBody(body)
            )
        )

      val secondState = "should now succeed"

      stub(Scenario.STARTED, secondState, BAD_REQUEST, "error")
      stub(secondState, secondState, OK, balanceResponseBody(grn))

      val hc = HeaderCarrier()

      whenReady(oneRetryConnector.getBalanceRequest(grn, hc).value) {
        case Right(_) => verify(loggerMock, times(1)).error(anyString())(any())
        case Left(ex) => fail(s"Failed with ${ex.toString}")
      }
    }

    "correctly derives not found status code from message for grn which cannot be found" in {
      val grn = GuaranteeReferenceNumber("abc")
      server.resetAll() // Need to reset due to the forAll - it's technically the same test

      server.stubFor(
        get(
          urlEqualTo(balanceUri(grn))
        ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
          .withHeader(HeaderNames.ACCEPT, equalTo("application/json"))
          .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
              .withBody(grnNotFoundResponseBody(grn))
          )
      )

      val hc = HeaderCarrier()

      whenReady(noRetriesConnector.getBalanceRequest(grn, hc).value) {
        case Left(x) if x == ConnectorError.GrnNotFound =>
          verify(loggerMock, times(1)).error(anyString())(any())
        case x =>
          fail(s"Left was not a RoutingError.Upstream (got $x)")
      }
    }

    "handle exceptions by returning an HttpResponse with status code 500" in {
      val httpClientV2 = mock[HttpClientV2]

      val hc        = HeaderCarrier()
      val connector = new EISConnectorImpl("Failure", connectorConfig, TestHelpers.headerCarrierConfig, httpClientV2, NoRetries, new TestMetrics)

      when(httpClientV2.get(any[URL])(any[HeaderCarrier])).thenReturn(new FakeRequestBuilder)

      whenReady(connector.getBalanceRequest(GuaranteeReferenceNumber("abc"), hc).value) {
        case Left(x) if x.isInstanceOf[ConnectorError.Unexpected] => x.asInstanceOf[ConnectorError.Unexpected].cause.get mustBe a[RuntimeException]
        case _                                                    => fail("Left was not a RoutingError.Unexpected")
      }
    }
  }

  "retryLogging" should {

    val testCases: Seq[(String, Either[ConnectorError, HttpResponse])] = Seq(
      "Unexpected Error"      -> Left(ConnectorError.Unexpected("bleh", None)),
      "Invalid access code"   -> Left(ConnectorError.InvalidAccessCode),
      "GRN not found"         -> Left(ConnectorError.GrnNotFound),
      "Failed to deserialise" -> Left(ConnectorError.FailedToDeserialise),
      "Success"               -> Right(mock[HttpResponse])
    )

    testCases foreach {
      entry =>
        s"always return unit for ${entry._1}" in {
          noRetriesConnectorWithRetryLogging.retryLogging(entry._2, RetryDetails.GivingUp(1, 20.seconds))
          succeed // i.e. an exception wasn't thrown
        }
    }
  }

}
