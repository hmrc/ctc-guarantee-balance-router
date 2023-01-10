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
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.ctcguaranteebalancerouter.models.CountryCode
import uk.gov.hmrc.ctcguaranteebalancerouter.models.GuaranteeReferenceNumber
import uk.gov.hmrc.ctcguaranteebalancerouter.models.errors.CountryExtractionError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[CountryExtractionServiceImpl])
trait CountryExtractionService {

  def extractCountry(grn: GuaranteeReferenceNumber): EitherT[Future, CountryExtractionError, CountryCode]

}

class CountryExtractionServiceImpl @Inject() (implicit ec: ExecutionContext) extends CountryExtractionService {
  private lazy val grnFormat = """[0-9]{2}([A-Z]{2})[A-Z0-9]{12}[0-9]([A-Z][0-9]{6})?""".r

  override def extractCountry(grn: GuaranteeReferenceNumber): EitherT[Future, CountryExtractionError, CountryCode] =
    grn.value match {
      case grnFormat("GB", _) => EitherT.rightT[Future, CountryExtractionError](CountryCode.Gb)
      case grnFormat("XI", _) => EitherT.rightT[Future, CountryExtractionError](CountryCode.Xi)
      case grnFormat(x, _)    => EitherT.leftT[Future, CountryCode](CountryExtractionError.InvalidCountryCode(x))
      case _                  => EitherT.leftT[Future, CountryCode](CountryExtractionError.InvalidFormat(grn))
    }

}
