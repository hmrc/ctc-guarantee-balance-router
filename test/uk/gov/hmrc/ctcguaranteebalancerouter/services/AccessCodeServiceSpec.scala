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

package uk.gov.hmrc.ctcguaranteebalancerouter.services

import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import uk.gov.hmrc.ctcguaranteebalancerouter.connectors.EISConnector
import uk.gov.hmrc.ctcguaranteebalancerouter.fakes.connectors.FakeEISConnectorProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.AccessCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.RoutingError
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.Generators
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.concurrent.ExecutionContext.Implicits.global

class AccessCodeServiceSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with MockitoSugar with ScalaFutures with Generators {

  "AccessCodeService#ensureAccessCodeValid" - {

    import org.scalatest.concurrent.PatienceConfiguration.Timeout

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "on valid access code, return a Right of Unit" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[CountryCode]
    ) {
      (grn, accessCode, countryCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.postAccessCodeRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.rightT(Json.obj("GRN" -> grn.value, "accessCode" -> accessCode.value)))

        val sut = new AccessCodeServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.ensureAccessCodeValid(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Right(())
        }
    }

    "on invalid access code, return a Left of AccessCodeError.InvalidAccessCode" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[CountryCode]
    ) {
      (grn, accessCode, countryCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.postAccessCodeRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.rightT(Json.obj("GRN" -> grn.value, "accessCode" -> "AABCD"))) // we know this is invalid

        val sut = new AccessCodeServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.ensureAccessCodeValid(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(AccessCodeError.InvalidAccessCode)
        }
    }

    "on invalid Json, return a Left of AccessCodeError.InvalidJson" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[CountryCode]
    ) {
      (grn, accessCode, countryCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.postAccessCodeRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.rightT(Json.obj("grn" -> grn.value, "nope" -> "AABCD")))

        val sut = new AccessCodeServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.ensureAccessCodeValid(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(AccessCodeError.InvalidJson)
        }
    }

    "on an upstream failure, return a Left of AccessCodeError.NotFound if it is a 400 or 404 error" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[CountryCode],
      Gen.oneOf(
        BAD_REQUEST,
        NOT_FOUND
      )
    ) {
      (grn, accessCode, countryCode, errorCode) =>
        val error         = RoutingError.Upstream(UpstreamErrorResponse("Nope", errorCode))
        val mockConnector = mock[EISConnector]
        when(mockConnector.postAccessCodeRequest(GuaranteeReferenceNumber(any()), any())(any())).thenReturn(EitherT.leftT(error))

        val sut = new AccessCodeServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.ensureAccessCodeValid(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(AccessCodeError.NotFound)
        }
    }

    "on an upstream failure, return a Left of AccessCodeError.Routing if it is a 500 error" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[AccessCode],
      arbitrary[CountryCode]
    ) {
      (grn, accessCode, countryCode) =>
        val error         = RoutingError.Upstream(UpstreamErrorResponse("Nope", INTERNAL_SERVER_ERROR))
        val mockConnector = mock[EISConnector]
        when(mockConnector.postAccessCodeRequest(GuaranteeReferenceNumber(any()), any())(any())).thenReturn(EitherT.leftT(error))

        val sut = new AccessCodeServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.ensureAccessCodeValid(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(AccessCodeError.Routing(error))
        }
    }
  }

}
