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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.ctcguaranteebalancerouter.connectors.EISConnector
import uk.gov.hmrc.ctcguaranteebalancerouter.fakes.connectors.FakeEISConnectorProvider
import uk.gov.hmrc.ctcguaranteebalancerouter.models.Balance
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CurrencyCL
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.BalanceRetrievalError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.ConnectorError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.responses.BalanceResponse
import uk.gov.hmrc.ctcguaranteebalancerouter.utils.Generators
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global

class BalanceRetrievalServiceSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with MockitoSugar with ScalaFutures with Generators {

  "BalanceRetrievalService#getBalance" - {

    import org.scalatest.concurrent.PatienceConfiguration.Timeout

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "on valid GRN, return a Right of the balance" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[BalanceResponse],
      arbitrary[CountryCode],
      arbitraryAccessCode.arbitrary
    ) {
      (grn, balanceResponse, countryCode, accessCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.getBalanceRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.rightT(balanceResponse))

        val sut = new BalanceRetrievalServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.getBalance(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Right(balanceResponse)
        }
    }

    "on invalid Json, return a Left of BalanceRetrievalError.InvalidJson" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[CountryCode],
      arbitraryAccessCode.arbitrary
    ) {
      (grn, countryCode, accessCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.getBalanceRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.leftT(ConnectorError.FailedToDeserialise))

        val sut = new BalanceRetrievalServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.getBalance(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(BalanceRetrievalError.FailedToDeserialise)
        }
    }

    "on an unexpected error, return a Left of BalanceRetrievalError.Unexpected" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[CountryCode],
      arbitraryAccessCode.arbitrary
    ) {
      (grn, countryCode, accessCode) =>
        val error         = new IllegalStateException()
        val mockConnector = mock[EISConnector]
        when(mockConnector.getBalanceRequest(GuaranteeReferenceNumber(any()), any())(any()))
          .thenReturn(EitherT.leftT(ConnectorError.Unexpected("nope", Some(error))))

        val sut = new BalanceRetrievalServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.getBalance(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(BalanceRetrievalError.Unexpected("nope", Some(error)))
        }
    }

    "on a not found, return a Left of BalanceRetrievalError.GrnNotFound" in forAll(
      arbitrary[GuaranteeReferenceNumber],
      arbitrary[CountryCode],
      arbitraryAccessCode.arbitrary
    ) {
      (grn, countryCode, accessCode) =>
        val mockConnector = mock[EISConnector]
        when(mockConnector.getBalanceRequest(GuaranteeReferenceNumber(any()), any())(any())).thenReturn(EitherT.leftT(ConnectorError.GrnNotFound))

        val sut = new BalanceRetrievalServiceImpl(FakeEISConnectorProvider(mockConnector, mockConnector))

        whenReady(sut.getBalance(grn, accessCode, countryCode).value, Timeout(1.second)) {
          _ mustBe Left(BalanceRetrievalError.GrnNotFound)
        }
    }
  }

}
