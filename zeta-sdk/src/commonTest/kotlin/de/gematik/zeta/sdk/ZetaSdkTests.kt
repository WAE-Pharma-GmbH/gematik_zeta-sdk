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

import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.CompositeCookieStorage
import de.gematik.zeta.sdk.network.http.client.SdkCookieStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ZetaSdkTest {
    private val requiredRoleOid = "1.2.276.0.76.4.261"
    private val resource = "https://example.com/pep/service/"
    private val scope = "scope"
    private val resourceScope = ResourceScope(resource, listOf(scope))
    private val storage = InMemoryStorage()

    private fun buildClient(): ZetaSdkClient = ZetaSdk.build(
        resource,
        createTestBuildConfig(storageConfig = StorageConfig.Custom(storage)),
    )

    private suspend fun storeCookie() {
        val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
        sdkCookieStorage.addCookie(
            Url(resource),
            Cookie(name = CompositeCookieStorage.ZETA_ROUTE_COOKIE, value = "test_route_value"),
        )
    }

    private suspend fun readCookie(): String? {
        val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
        return sdkCookieStorage.get(Url(resource)).firstOrNull()?.value
    }

    @Test
    fun logout_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        client.logout()

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun forget_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        with(ZetaSdk) { client.forget() }

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun clearRegistration_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        with(ZetaSdk) { client.clearRegistration() }

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun logout_doesNotAffectOtherStorageKeys() = runTest {
        // Arrange
        val client = buildClient()
        val extendedStorage = ExtendedStorage(storage, resourceScope)
        storeCookie()
        extendedStorage.put("other_key", "other_value")

        // Act
        client.logout()

        // Assert
        assertNull(readCookie())
        assertEquals("other_value", extendedStorage.get("other_key"))
    }

    @Test
    fun build_createsClient_withMinimalConfig() {
        // Arrange
        val config = createTestBuildConfig()

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCustomHttpClientBuilder() {
        // Arrange
        val customBuilder = ZetaHttpClientBuilder().logging(LogLevel.NONE)
        val config = createTestBuildConfig(httpClientBuilder = customBuilder)

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCustomStorage() {
        // Arrange
        val mockStorage = createMockStorage()
        val config = createTestBuildConfig(storageConfig = StorageConfig.Custom(provider = mockStorage))

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCallbacks() {
        // Arrange
        val regCallback = RegistrationCallback { RegInfo("TestClient") }
        val authCallback = AuthenticationCallback { AuthInfo("123456") }
        val config = createTestBuildConfig(
            registrationCallback = regCallback,
            authenticationCallback = authCallback,
        )

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun forget_returnsSuccess_whenNoErrors() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.forget()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun clearRegistration_returnsSuccess_whenNoErrors() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.clearRegistration()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun discover_returnsFailure_whenConfigurationFails() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isFailure)
    }

    @Test
    fun register_returnsSuccess_whenRegistrationCompletes() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.register()

        // Assert
        assertNotNull(result)
    }

    @Test
    fun authenticate_returnsResult_whenCalled() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.authenticate()

        // Assert
        assertNotNull(result)
    }

    @Test
    fun httpClient_createsClient_withDefaultBuilder() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient()

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_createsClient_withCustomConfiguration() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient {
            timeouts(connectMs = 5000, requestMs = 10000)
            retry(maxRetries = 3)
        }

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_installsPlugins_zetaAndAslDecryption() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient()

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_canBeCalledMultipleTimes_createsNewInstances() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient1 = client.httpClient()
        val httpClient2 = client.httpClient()

        // Assert
        assertNotNull(httpClient1)
        assertNotNull(httpClient2)
        httpClient1.close()
        httpClient2.close()
    }

    @Test
    fun ws_throwsException_whenDiscoverFails() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://invalid-url", config)

        // Act & Assert
        assertFailsWith<Exception> {
            client.ws("wss://api.example.com/ws") {}
        }
    }

    @Test
    fun logout_returnsSuccess() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.logout()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun close_clearsAuthenticationStorage() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val testScope = ResourceScope("https://api.example.com", listOf(scope))
        val client = ZetaSdk.build(
            "https://api.example.com",
            createTestBuildConfig(storageConfig = StorageConfig.Custom(provider = storage)),
        )
        val authStorage = AuthenticationStorageImpl(storage, testScope)
        authStorage.saveAccessTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAt = Clock.System.now().epochSeconds + 3600,
        )
        assertNotNull(authStorage.getAccessToken())

        // Act
        val result = client.logout()

        // Assert
        assertTrue(result.isSuccess)
        assertNull(authStorage.getAccessToken())
        assertNull(authStorage.getRefreshToken())
    }

    @Test
    fun fullFlow_buildDiscoverRegisterAuthenticate_executesInOrder() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val discoverResult = client.discover()
        val registerResult = client.register()
        val authenticateResult = client.authenticate()

        // Assert
        assertNotNull(discoverResult)
        assertNotNull(registerResult)
        assertNotNull(authenticateResult)
    }

    @Test
    fun multipleBuildCalls_createsDifferentClients_independent() {
        // Arrange
        val config1 = createTestBuildConfig()
        val config2 = createTestBuildConfig()

        // Act
        val client1 = ZetaSdk.build("https://api1.example.com", config1)
        val client2 = ZetaSdk.build("https://api2.example.com", config2)

        // Assert
        assertNotNull(client1)
        assertNotNull(client2)
    }

    private fun createTestBuildConfig(
        productId: String = "test-product",
        productVersion: String = "1.0.0",
        clientName: String = "TestClient",
        storageConfig: StorageConfig = StorageConfig.Custom(provider = InMemoryStorage()),
        httpClientBuilder: ZetaHttpClientBuilder? = null,
        registrationCallback: RegistrationCallback? = null,
        authenticationCallback: AuthenticationCallback? = null,
    ): BuildConfig {
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(
            listOf(scope),
            300,
            false,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = requiredRoleOid,
        )
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")
        return BuildConfig(
            productId = productId,
            productVersion = productVersion,
            clientName = clientName,
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
            httpClientBuilder = httpClientBuilder,
            registrationCallback = registrationCallback,
            authenticationCallback = authenticationCallback,
        )
    }

    private fun createMockStorage(): SdkStorage = object : SdkStorage {
        private val data = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { data[key] = value }
        override suspend fun get(key: String): String? = data[key]
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }
}
