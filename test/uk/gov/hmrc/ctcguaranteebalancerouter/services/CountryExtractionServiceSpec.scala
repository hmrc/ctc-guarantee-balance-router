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

import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.CountryExtractionError
import uk.gov.hmrc.ctcguaranteebalancerouter.uils.Generators

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class CountryExtractionServiceSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ScalaFutures with Generators {

  "CountryExtractionService#extractCountry" - {

    val sut = new CountryExtractionServiceImpl()

    "on valid GB GRN, get Gb back" in forAll(guaranteeReferenceNumberGenerator(Gen.const("GB"))) {
      grn =>
        whenReady(sut.extractCountry(grn).value) {
          _ mustBe Right(CountryCode.Gb)
        }
    }

    "on valid XI GRN, get Xi back" in forAll(guaranteeReferenceNumberGenerator(Gen.const("XI"))) {
      grn =>
        whenReady(sut.extractCountry(grn).value) {
          _ mustBe Right(CountryCode.Xi)
        }
    }

    "on non UK but valid GRN, get invalid country code error back" in forAll(guaranteeReferenceNumberGenerator(Gen.const("FR"))) {
      grn =>
        val result = Await.result(sut.extractCountry(grn).value, 2.seconds)
        result mustBe Left(CountryExtractionError.InvalidCountryCode("FR"))
    }

    "on non UK but valid GRN, get invalid format error back" in forAll(Gen.stringOfN(5, Gen.alphaUpperChar).map(GuaranteeReferenceNumber)) {
      grn =>
        val result = Await.result(sut.extractCountry(grn).value, 2.seconds)
        result mustBe Left(CountryExtractionError.InvalidFormat(grn))
    }
  }

}
