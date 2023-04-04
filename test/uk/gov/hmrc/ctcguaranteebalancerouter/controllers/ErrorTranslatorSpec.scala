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

import cats.syntax.all._
import org.mockito.MockitoSugar
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.BalanceRetrievalError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.CountryExtractionError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.PresentationError

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ErrorTranslatorSpec extends AnyFreeSpec with Matchers with OptionValues with ScalaFutures with MockitoSugar with ScalaCheckDrivenPropertyChecks {

  object Harness extends ErrorTranslator

  import Harness._

  "ErrorTranslator#asPresentation" - {
    "for a success returns the same right" in {
      val input = Right[CountryExtractionError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for an error returns a left with the appropriate presentation error" in {
      val input = Left[CountryExtractionError, Unit](CountryExtractionError.InvalidCountryCode("FR")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(PresentationError.badRequestError("GRN was provided for FR, this is not a GB or XI guarantee"))
      }
    }
  }

  "CountryExtractionError error" - {
    "InvalidCountryCode error is a Bad Request" in forAll(Gen.stringOfN(2, Gen.alphaUpperChar)) {
      countryCode =>
        countryExtractionErrorConverter.convert(CountryExtractionError.InvalidCountryCode(countryCode)) mustBe
          PresentationError.badRequestError(s"GRN was provided for $countryCode, this is not a GB or XI guarantee")
    }

    "InvalidFormat is a Bad Request" in forAll(Gen.stringOfN(2, Gen.alphaUpperChar)) {
      badGrn =>
        countryExtractionErrorConverter.convert(CountryExtractionError.InvalidFormat(GuaranteeReferenceNumber(badGrn))) mustBe
          PresentationError.badRequestError(s"GRN $badGrn is not in the correct format")
    }
  }

  "BalanceRetrievalError error" - {
    "FailedToDeserialise error is an Internal Service Error" in {
      balanceRetrievalErrorConveter.convert(BalanceRetrievalError.FailedToDeserialise) mustBe PresentationError.internalServiceError()
    }

    "NotFound is a not found" in {
      balanceRetrievalErrorConveter.convert(BalanceRetrievalError.GrnNotFound) mustBe PresentationError.notFoundError("GRN not found")
    }

    "Unexpected Error with no exception is an Internal Service Error" in {
      balanceRetrievalErrorConveter.convert(BalanceRetrievalError.Unexpected("bleh", None)) mustBe PresentationError.internalServiceError()
    }

    "Unexpected Error with an exception is an Internal Service Error" in {
      balanceRetrievalErrorConveter.convert(BalanceRetrievalError.Unexpected("bleh", Some(new IllegalStateException()))) mustBe PresentationError
        .internalServiceError()
    }
  }

  "AccessCodeError error" - {

    "InvalidAccessCode is a forbidden" in {
      accessCodeErrorConverter.convert(AccessCodeError.InvalidAccessCode) mustBe PresentationError.forbiddenError("Access code did not match")
    }

    "FailedToDeserialise error is an Internal Service Error" in {
      accessCodeErrorConverter.convert(AccessCodeError.FailedToDeserialise) mustBe PresentationError.internalServiceError()
    }

    "Unexpected Error with no exception is an Internal Service Error" in {
      accessCodeErrorConverter.convert(AccessCodeError.Unexpected("bleh", None)) mustBe PresentationError.internalServiceError()
    }

    "Unexpected Error with an exception is an Internal Service Error" in {
      accessCodeErrorConverter.convert(AccessCodeError.Unexpected("bleh", Some(new IllegalStateException()))) mustBe PresentationError.internalServiceError()
    }
  }

}
