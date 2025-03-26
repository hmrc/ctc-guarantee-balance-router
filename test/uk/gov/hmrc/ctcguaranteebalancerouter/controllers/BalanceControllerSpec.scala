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

package uk.gov.hmrc.ctcguaranteebalancerouter.controllers

import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.invocation.InvocationOnMock
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.FORBIDDEN
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.http.Status.UNAUTHORIZED
import play.api.libs.json.Json
import play.api.mvc.ActionBuilder
import play.api.mvc.AnyContent
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.ctcguaranteebalancerouter.config.AppConfig
import uk.gov.hmrc.ctcguaranteebalancerouter.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.controllers.actions.InternalAuthActionProviderImpl
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.requests
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.BalanceResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.services.AccessCodeService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.BalanceRetrievalService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.CountryExtractionService
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.Generators
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType
import uk.gov.hmrc.internalauth.client.Retrieval.EmptyRetrieval
import uk.gov.hmrc.internalauth.client.test.BackendAuthComponentsStub
import uk.gov.hmrc.internalauth.client.test.StubBehaviour

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BalanceControllerSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaCheckDrivenPropertyChecks with Generators {

  "BalanceController#postBalance" - {

    "with InternalAuth disabled" - {
      object PassthroughAuthProvider extends InternalAuthActionProvider {
        override def apply(predicate: Predicate)(implicit ec: ExecutionContext): ActionBuilder[Request, AnyContent] =
          DefaultActionBuilder(stubControllerComponents().parsers.anyContent)(ec)
      }

      "valid GRN will return a balance" in forAll(
        arbitrary[GuaranteeReferenceNumber],
        arbitrary[AccessCode],
        arbitrary[BalanceResponse]
      ) {
        (grn, accessCode, balanceResponse) =>
          val acs = mock[AccessCodeService]
          val brs = mock[BalanceRetrievalService]
          val ces = mock[CountryExtractionService]

          when(
            acs.ensureAccessCodeValid(
              GuaranteeReferenceNumber(eqTo(grn.value)),
              AccessCode(eqTo(accessCode.value)),
              any[CountryCode]
            )(any(), any())
          ).thenReturn(EitherT.rightT[Future, AccessCodeError](()))

          when(brs.getBalance(GuaranteeReferenceNumber(eqTo(grn.value)), AccessCode(eqTo(accessCode.value)), any[CountryCode])(any(), any()))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](balanceResponse))

          when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

          val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
          val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), requests.RouterBalanceRequest(accessCode)))

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.obj("balance" -> balanceResponse.balance.value, "currencyCL" -> balanceResponse.currencyCL.value)
      }

      "invalid GRN will return a not found" in forAll(
        arbitrary[GuaranteeReferenceNumber],
        arbitrary[AccessCode]
      ) {
        (grn, accessCode) =>
          val acs = mock[AccessCodeService]
          val brs = mock[BalanceRetrievalService]
          val ces = mock[CountryExtractionService]

          when(
            acs.ensureAccessCodeValid(
              GuaranteeReferenceNumber(eqTo(grn.value)),
              AccessCode(eqTo(accessCode.value)),
              any[CountryCode]
            )(any(), any())
          ).thenReturn(EitherT.leftT[Future, AccessCodeError](AccessCodeError.GrnNotFound))

          when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

          val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
          val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), requests.RouterBalanceRequest(accessCode)))

          status(result) mustBe NOT_FOUND
          contentAsJson(result) mustBe Json.obj("code" -> "NOT_FOUND", "message" -> "GRN not found")
      }

      "invalid access code will return a forbidden" in forAll(
        arbitrary[GuaranteeReferenceNumber],
        arbitrary[AccessCode]
      ) {
        (grn, accessCode) =>
          val acs = mock[AccessCodeService]
          val brs = mock[BalanceRetrievalService]
          val ces = mock[CountryExtractionService]

          when(
            acs.ensureAccessCodeValid(
              GuaranteeReferenceNumber(eqTo(grn.value)),
              AccessCode(eqTo(accessCode.value)),
              any[CountryCode]
            )(any(), any())
          ).thenReturn(EitherT.left(Future.successful(AccessCodeError.InvalidAccessCode)))

          when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

          val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
          val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), requests.RouterBalanceRequest(accessCode)))

          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe Json.obj("code" -> "FORBIDDEN", "message" -> "Access code did not match")
      }

      "invalid guarantee type will return a bad request" in forAll(
        arbitrary[GuaranteeReferenceNumber],
        arbitrary[AccessCode]
      ) {
        (grn, accessCode) =>
          val acs = mock[AccessCodeService]
          val brs = mock[BalanceRetrievalService]
          val ces = mock[CountryExtractionService]

          when(
            acs.ensureAccessCodeValid(
              GuaranteeReferenceNumber(eqTo(grn.value)),
              AccessCode(eqTo(accessCode.value)),
              any[CountryCode]
            )(any(), any())
          ).thenReturn(EitherT.left(Future.successful(AccessCodeError.InvalidGuaranteeType)))

          when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

          val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
          val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), requests.RouterBalanceRequest(accessCode)))

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj("code" -> "INVALID_GUARANTEE_TYPE", "message" -> "Guarantee type is not supported")
      }

      "backend failure will return a 500" in forAll(
        arbitrary[GuaranteeReferenceNumber],
        arbitrary[AccessCode],
        arbitrary[BalanceResponse]
      ) {
        (grn, accessCode, balanceResponse) =>
          val acs = mock[AccessCodeService]
          val brs = mock[BalanceRetrievalService]
          val ces = mock[CountryExtractionService]

          when(
            acs.ensureAccessCodeValid(
              GuaranteeReferenceNumber(eqTo(grn.value)),
              AccessCode(eqTo(accessCode.value)),
              any[CountryCode]
            )(any(), any())
          ).thenReturn(EitherT.left(Future.successful(AccessCodeError.FailedToDeserialise)))

          when(brs.getBalance(GuaranteeReferenceNumber(eqTo(grn.value)), AccessCode(eqTo(accessCode.value)), any[CountryCode])(any(), any()))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](balanceResponse))

          when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
            .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

          val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
          val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), requests.RouterBalanceRequest(accessCode)))

          status(result) mustBe INTERNAL_SERVER_ERROR
      }

    }
  }

  "with Internal Auth enabled" - {

    val mockAppConfig = mock[AppConfig]
    when(mockAppConfig.internalAuthEnabled).thenReturn(true)

    "when provided a valid token" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[BalanceResponse]
    ) {
      (grn, accessCode, balanceResponse) =>
        val acs = mock[AccessCodeService]
        val brs = mock[BalanceRetrievalService]
        val ces = mock[CountryExtractionService]

        val mockStubAuth = mock[StubBehaviour]
        when(mockStubAuth.stubAuth(any(), eqTo(EmptyRetrieval))).thenAnswer {
          (invocation: InvocationOnMock) =>
            val incomingPredicate = invocation.getArgument(0, classOf[Option[Predicate]])
            incomingPredicate match {
              case Some(Predicate.Permission(Resource(ResourceType("ctc-guarantee-balance-router"), ResourceLocation("balance")), IAAction("READ"))) =>
                Future.successful(())
              case _ =>
                Future.failed(UpstreamErrorResponse("not authorized", UNAUTHORIZED))
            }
        }

        val stubbedInternalAuthClient  = BackendAuthComponentsStub(mockStubAuth)(stubControllerComponents(), global)
        val internalAuthActionProvider = new InternalAuthActionProviderImpl(mockAppConfig, stubbedInternalAuthClient, stubControllerComponents())

        when(
          acs.ensureAccessCodeValid(
            GuaranteeReferenceNumber(eqTo(grn.value)),
            AccessCode(eqTo(accessCode.value)),
            any[CountryCode]
          )(any(), any())
        ).thenReturn(EitherT.rightT[Future, AccessCodeError](()))

        when(brs.getBalance(GuaranteeReferenceNumber(eqTo(grn.value)), AccessCode(eqTo(accessCode.value)), any[CountryCode])(any(), any()))
          .thenReturn(EitherT.rightT[Future, AccessCodeError](balanceResponse))

        when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
          .thenReturn(EitherT.rightT[Future, AccessCodeError](CountryCode.Gb))

        val sut = new BalanceController(acs, brs, ces, stubControllerComponents(), internalAuthActionProvider)
        val result =
          sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(Seq(HeaderNames.AUTHORIZATION -> "Token 1234")), requests.RouterBalanceRequest(accessCode)))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.obj("balance" -> balanceResponse.balance.value, "currencyCL" -> balanceResponse.currencyCL.value)

    }
  }
}
