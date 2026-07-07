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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.hash
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigurationStorageImpl].
 */
class ConfigurationStorageTest {
    private fun buildStorage(
        sdk: InMemoryStorage = InMemoryStorage(),
        fqdn: String = "https://api.example.com",
        scope: String = "scope-a",
    ): Pair<ConfigurationStorageImpl, InMemoryStorage> {
        val resourceScope = ResourceScope(fqdn, listOf(scope))
        return ConfigurationStorageImpl(sdk, resourceScope) to sdk
    }

    @Test
    fun getProtectedResource_returnsNull_whenResourceIsMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsNull_whenDeserializationFails() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()
        sdk.put(hash("pr:resource"), "not-a-json")

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsCorrectValue() = runTest {
        // Arrange
        val (storage, _) = buildStorage(fqdn = "https://api.example.com")
        val goodJson = getDummyProtectedResourceObject("https://api.example.com", listOf("https://auth.example.com"))
        storage.saveProtectedResource(goodJson)

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNotNull(result)
        assertEquals("https://api.example.com", result.resource)
    }

    @Test
    fun linkResourceToAuthorizationServer_createsLink() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        val asMeta = getDummyAuthServerObject("https://auth.example.com", "https://auth.example.com/token")

        // Act
        storage.linkResourceToAuthorizationServer(asMeta)

        // Assert
        val authServer = storage.getAuthServer()
        assertNotNull(authServer)
        assertEquals("https://auth.example.com", authServer.issuer)
        assertEquals("https://auth.example.com/token", authServer.tokenEndpoint)
    }

    @Test
    fun linkResourceToAuthorizationServer_doesNotDuplicateLinkForSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        val meta = getDummyAuthServerObject("https://auth.example.com", "https://auth.example.com/token")

        // Act
        storage.linkResourceToAuthorizationServer(meta)
        storage.linkResourceToAuthorizationServer(meta.copy())

        // Assert
        assertEquals(1, storage.getAuthServers().size)
    }

    @Test
    fun linkResourceToAuthorizationServer_overwritesAuthorizationServer_forSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        val metaV1 = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")
        val metaV2 = getDummyAuthServerObject("https://auth.example.com", "/tokenV2")

        // Act
        storage.linkResourceToAuthorizationServer(metaV1)

        // Assert
        assertTrue(storage.getAuthServer()!!.tokenEndpoint.endsWith("/tokenV1"))

        // Act
        storage.linkResourceToAuthorizationServer(metaV2)

        // Assert
        assertTrue(storage.getAuthServer()!!.tokenEndpoint.endsWith("/tokenV2"))
    }

    @Test
    fun getAuthServer_returnsNull_whenNoAuthServerFound() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        val meta = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")
        storage.linkResourceToAuthorizationServer(meta)

        storage.clear()

        // Act
        val result = storage.getAuthServer()

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServer_returnsNull_whenAuthServerDataIsCorrupted() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getAuthServer()

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServers_returnsEmptyList() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun getAuthServers_returnsOnlyDataThatCanBeDeserialized() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        storage.linkResourceToAuthorizationServer(getDummyAuthServerObject("https://test.example.com"))

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_returnsListOfLinkedAuthServers() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        storage.linkResourceToAuthorizationServer(getDummyAuthServerObject())

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_doesNotCreateDuplicatesForSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        storage.linkResourceToAuthorizationServer(getDummyAuthServerObject())
        storage.linkResourceToAuthorizationServer(getDummyAuthServerObject())

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    @Test
    fun clear_removesCacheAndStorage() = runTest {
        // Arrange
        val (storage, _) = buildStorage()
        storage.saveProtectedResource(getDummyProtectedResourceObject("https://api.example.com"))
        storage.linkResourceToAuthorizationServer(getDummyAuthServerObject("https://auth.example.com", "/token"))

        // Act
        storage.clear()

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getAuthServer())
        assertTrue(storage.getAuthServers().isEmpty())
    }

    @Test
    fun saveProtectedResource_throwsException_whenInvalidData() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Assert
        assertFailsWith<SerializationException> {
            // Act
            storage.saveProtectedResource("{invalid-json}")
        }
    }
}
