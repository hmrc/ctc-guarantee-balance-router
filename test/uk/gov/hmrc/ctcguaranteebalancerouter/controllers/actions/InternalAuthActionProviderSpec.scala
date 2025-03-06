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

package uk.gov.hmrc.ctcguaranteebalancerouter.controllers.actions

import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.mvc.ActionBuilder
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Request
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.ctcguaranteebalancerouter.config.AppConfig
import uk.gov.hmrc.internalauth.client.AuthenticatedRequest
import uk.gov.hmrc.internalauth.client.BackendAuthComponents
import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType
import uk.gov.hmrc.internalauth.client.Retrieval.EmptyRetrieval
import org.mockito.ArgumentMatchers.{any => anyArg}

import scala.compiletime.ops.any
import scala.concurrent.ExecutionContext.Implicits.global

class InternalAuthActionProviderSpec extends AnyFreeSpec with Matchers with MockitoSugar {

  type AuthReq[R] = AuthenticatedRequest[R, Unit]

  "InternalAuthActionProvider#apply" - {

    "when internal auth is disabled" - {

      "return action should not be the auth action" in {

        val appConfig = mock[AppConfig]
        when(appConfig.internalAuthEnabled).thenReturn(false)

        val mockBackendAuthComponents = mock[BackendAuthComponents]

        val samplePermission = Predicate.Permission(Resource(ResourceType("ctc-guarantee-balance-router"), ResourceLocation("balance")), IAAction("READ"))
        val _                = new InternalAuthActionProviderImpl(appConfig, mockBackendAuthComponents, stubControllerComponents())
        verify(mockBackendAuthComponents, times(0)).authorizedAction(samplePermission)
      }

      "should return DefaultActionBuilder" in {
        val appConfig = mock[AppConfig]
        when(appConfig.internalAuthEnabled).thenReturn(false)

        val backendAuthComponents    = mock[BackendAuthComponents]
        val cc: ControllerComponents = stubControllerComponents()

        val provider       = new InternalAuthActionProviderImpl(appConfig, backendAuthComponents, cc)
        val dummyPredicate = mock[Predicate]
        val actionBuilder  = provider(dummyPredicate)

        actionBuilder mustBe a[DefaultActionBuilder]
      }

    }

    "when internal auth is enabled" - {

      "return action should not be the auth action" in {

        val appConfig = mock[AppConfig]
        when(appConfig.internalAuthEnabled).thenReturn(true)

        val mockBackendAuthComponents = mock[BackendAuthComponents]

        val samplePermission = Predicate.Permission(Resource(ResourceType("ctc-guarantee-balance-router"), ResourceLocation("balance")), IAAction("READ"))
        val _                = new InternalAuthActionProviderImpl(appConfig, mockBackendAuthComponents, stubControllerComponents()).apply(samplePermission)
        verify(mockBackendAuthComponents, times(1)).authorizedAction(samplePermission)
      }

    }

  }
}
