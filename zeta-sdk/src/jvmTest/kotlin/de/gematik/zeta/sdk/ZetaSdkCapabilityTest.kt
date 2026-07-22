/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
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
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.statement.HttpResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ZetaSdkClientImplCapabilityTest {

    @Test
    fun discover_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun discover_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns
            CapabilityResult.Error("SERVICE_DISCOVERY_ERROR", "discovery error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("SERVICE_DISCOVERY_ERROR" in message)
        assertTrue("discovery error" in message)
    }

    @Test
    fun discover_treatsRetryRequestAsSuccess_documentingCurrentBehavior() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns
            CapabilityResult.RetryRequest()
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isSuccess, "documents current (possibly unintended) orThrow() behavior")
    }

    @Test
    fun register_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { clientRegistrationHandler.handle(FlowNeed.ClientRegistration, any()) } returns
            CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.register()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun register_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { clientRegistrationHandler.handle(FlowNeed.ClientRegistration, any()) } returns
            CapabilityResult.Error("REGISTRATION_FAILED_ERROR", "registration error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.register()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("REGISTRATION_FAILED_ERROR" in message)
        assertTrue("registration error" in message)
    }

    @Test
    fun authenticate_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { authHandler.handle(FlowNeed.Authentication, any()) } returns CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.authenticate()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun authenticate_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { authHandler.handle(FlowNeed.Authentication, any()) } returns
            CapabilityResult.Error("AUTH_FAILED_ERROR", "auth error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.authenticate()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("AUTH_FAILED_ERROR" in message)
        assertTrue("auth error" in message)
    }

    private val resource = "resx"
    private val scope = "scope"
    private val resourceScope = ResourceScope(resource, listOf(scope))
    private val configHandler: ConfigurationHandler = mockk()
    private val clientRegistrationHandler: ClientRegistrationHandler = mockk()
    private val authHandler: EnsureAccessTokenHandler = mockk()
    private val httpResponse: HttpResponse = mockk()

    private fun buildTestConfig(): BuildConfig {
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(
            listOf(scope),
            300,
            false,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = "1.2.276.0.76.4.261",
        )
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")
        return BuildConfig(
            productId = "test-product",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = StorageConfig.Custom(InMemoryStorage()),
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )
    }

    private fun ZetaSdkClientImpl.injectLazyDelegate(propertyName: String, value: Any) {
        val field = ZetaSdkClientImpl::class.java.getDeclaredField("$propertyName\$delegate")
        field.isAccessible = true
        field.set(this, lazyOf(value))
    }

    private fun buildClient(): ZetaSdkClientImpl {
        val client = ZetaSdkClientImpl(resourceScope, buildTestConfig())
        client.injectLazyDelegate("configHandler", configHandler)
        client.injectLazyDelegate("clientRegistrationHandler", clientRegistrationHandler)
        client.injectLazyDelegate("authHandler", authHandler)
        return client
    }
}
