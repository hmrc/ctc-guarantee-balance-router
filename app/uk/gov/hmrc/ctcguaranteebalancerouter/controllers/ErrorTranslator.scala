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
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.AccessCodeError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.BalanceRetrievalError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.CountryExtractionError
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.PresentationError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ErrorTranslator {

  implicit class ErrorConverter[E, A](value: EitherT[Future, E, A]) {

    def asPresentation(implicit c: Converter[E], ec: ExecutionContext): EitherT[Future, PresentationError, A] =
      value.leftMap(c.convert)
  }

  trait Converter[E] {
    def convert(input: E): PresentationError
  }

  implicit val accessCodeErrorConverter = new Converter[AccessCodeError] {

    override def convert(error: AccessCodeError): PresentationError = error match {
      case AccessCodeError.GrnNotFound         => PresentationError.notFoundError("GRN not found")
      case AccessCodeError.InvalidAccessCode   => PresentationError.forbiddenError("Access code did not match")
      case AccessCodeError.FailedToDeserialise => PresentationError.internalServiceError()
      case AccessCodeError.Unexpected(_, _)    => PresentationError.internalServiceError()
    }
  }

  implicit val balanceRetrievalErrorConveter = new Converter[BalanceRetrievalError] {

    override def convert(input: BalanceRetrievalError): PresentationError = input match {
      case BalanceRetrievalError.GrnNotFound         => PresentationError.notFoundError("GRN not found")
      case BalanceRetrievalError.FailedToDeserialise => PresentationError.internalServiceError()
      case BalanceRetrievalError.Unexpected(_, _)    => PresentationError.internalServiceError()
    }
  }

  implicit val countryExtractionErrorConverter = new Converter[CountryExtractionError] {

    override def convert(input: CountryExtractionError): PresentationError = input match {
      case CountryExtractionError.InvalidCountryCode(code) => PresentationError.badRequestError(s"GRN was provided for $code, this is not a GB or XI guarantee")
      case CountryExtractionError.InvalidFormat(grn)       => PresentationError.badRequestError(s"GRN ${grn.value} is not in the correct format")
    }
  }

}
