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
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.MockitoSugar
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.OK
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
import uk.gov.hmrc.ctcguaranteebalancerouter.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.Balance
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.RouterBalanceRequest
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.services.AccessCodeService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.BalanceRetrievalService
import uk.gov.hmrc.ctcguaranteebalancerouter.services.CountryExtractionService
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.Generators
import uk.gov.hmrc.internalauth.client.Predicate

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

class BalanceControllerSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaCheckDrivenPropertyChecks with Generators {

  "BalanceController#postBalance" - {

    object PassthroughAuthProvider extends InternalAuthActionProvider {
      override def apply(predicate: Predicate)(implicit ec: ExecutionContext): ActionBuilder[Request, AnyContent] =
        DefaultActionBuilder(stubControllerComponents().parsers.anyContent)(ec)
    }

    "valid GRN will return a balance" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[Balance]
    ) {
      (grn, accessCode, balance) =>
        val acs = mock[AccessCodeService]
        val brs = mock[BalanceRetrievalService]
        val ces = mock[CountryExtractionService]

        when(
          acs.ensureAccessCodeValid(
            GuaranteeReferenceNumber(eqTo(grn.value)),
            AccessCode(eqTo(accessCode.value)),
            any[CountryCode]
          )(any(), any())
        ).thenReturn(EitherT.rightT(()))

        when(brs.getBalance(GuaranteeReferenceNumber(eqTo(grn.value)), any[CountryCode])(any(), any()))
          .thenReturn(EitherT.rightT(balance))

        when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
          .thenReturn(EitherT.rightT(CountryCode.Gb))

        val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
        val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), RouterBalanceRequest(accessCode)))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.obj("balance" -> balance.value)
    }

    "backend failure will return a 500" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[Balance]
    ) {
      (grn, accessCode, balance) =>
        val acs = mock[AccessCodeService]
        val brs = mock[BalanceRetrievalService]
        val ces = mock[CountryExtractionService]

        when(
          acs.ensureAccessCodeValid(
            GuaranteeReferenceNumber(eqTo(grn.value)),
            AccessCode(eqTo(accessCode.value)),
            any[CountryCode]
          )(any(), any())
        ).thenReturn(EitherT.leftT(AccessCodeError.FailedToDeserialise))

        when(brs.getBalance(GuaranteeReferenceNumber(eqTo(grn.value)), any[CountryCode])(any(), any()))
          .thenReturn(EitherT.rightT(balance))

        when(ces.extractCountry(GuaranteeReferenceNumber(eqTo(grn.value))))
          .thenReturn(EitherT.rightT(CountryCode.Gb))

        val sut    = new BalanceController(acs, brs, ces, stubControllerComponents(), PassthroughAuthProvider)
        val result = sut.postBalance(grn)(FakeRequest("POST", "/", FakeHeaders(), RouterBalanceRequest(accessCode)))

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

  }
}
