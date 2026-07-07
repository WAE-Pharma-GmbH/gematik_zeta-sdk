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

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.sdk.network.http.client.CompositeCookieStorage.Companion.ZETA_ROUTE_COOKIE
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkCookieStorageTest {
    private val url = Url("https://example.com/path")
    private val resourceScope = ResourceScope("https://example.com", listOf("scope-a"))

    private fun createComposite(
        scope: ResourceScope = resourceScope,
    ): Pair<CompositeCookieStorage, SdkCookieStorage> {
        val sdkStorage = SdkCookieStorage(InMemoryStorage(), scope)
        return CompositeCookieStorage(sdkStorage) to sdkStorage
    }

    private fun createStorage(
        scope: ResourceScope = resourceScope,
    ): Pair<SdkCookieStorage, InMemoryStorage> {
        val storage = InMemoryStorage()
        return SdkCookieStorage(storage, scope) to storage
    }

    @Test
    fun get_returnsEmpty_whenNoCookieStored() = runTest {
        // Arrange
        val (storage, _) = createStorage()

        // Act & Assert
        assertTrue(storage.get(url).isEmpty())
    }

    @Test
    fun addCookie_storesZetaRoute_andGetReturnsIt() = runTest {
        // Arrange
        val (storage, _) = createStorage()

        // Act
        storage.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "abc123"))

        // Assert
        val cookies = storage.get(url)
        assertEquals(1, cookies.size)
        assertEquals(ZETA_ROUTE_COOKIE, cookies[0].name)
        assertEquals("abc123", cookies[0].value)
    }

    @Test
    fun addCookie_overwritesExistingZetaRoute() = runTest {
        // Arrange
        val (storage, _) = createStorage()
        storage.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "first"))

        // Act
        storage.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "second"))

        // Assert
        val cookies = storage.get(url)
        assertEquals(1, cookies.size)
        assertEquals("second", cookies[0].value)
    }

    @Test
    fun clearCookie_removesStoredValue() = runTest {
        // Arrange
        val (storage, _) = createStorage()
        storage.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "abc123"))

        // Act
        storage.clearCookie()

        // Assert
        assertTrue(storage.get(url).isEmpty())
    }

    @Test
    fun differentScopes_useDifferentStorageKeys() = runTest {
        // Arrange
        val underlyingStorage = InMemoryStorage()
        val scope1 = ResourceScope("https://host1.example.com", listOf("scope-a"))
        val scope2 = ResourceScope("https://host2.example.com", listOf("scope-a"))
        val storage1 = SdkCookieStorage(underlyingStorage, scope1)
        val storage2 = SdkCookieStorage(underlyingStorage, scope2)

        // Act
        storage1.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "value1"))

        // Assert
        assertTrue(storage2.get(url).isEmpty())
    }

    @Test
    fun sameScope_sharesStorageKey() = runTest {
        // Arrange
        val underlyingStorage = InMemoryStorage()
        val scope = ResourceScope("https://example.com", listOf("scope-a"))
        val storage1 = SdkCookieStorage(underlyingStorage, scope)
        val storage2 = SdkCookieStorage(underlyingStorage, scope)

        // Act
        storage1.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "shared"))

        // Assert
        assertEquals("shared", storage2.get(url)[0].value)
    }

    @Test
    fun addCookie_zetaRoute_isRoutedToSdkStorage() = runTest {
        // Arrange
        val (composite, sdkStorage) = createComposite()

        // Act
        composite.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "abc123"))

        // Assert
        val cookies = sdkStorage.get(url)
        assertEquals(1, cookies.size)
        assertEquals("abc123", cookies[0].value)
    }

    @Test
    fun addCookie_nonZetaRoute_isRoutedToDefaultStorage() = runTest {
        // Arrange
        val (composite, sdkStorage) = createComposite()

        // Act
        composite.addCookie(url, Cookie(name = "session", value = "xyz"))

        // Assert
        assertTrue(sdkStorage.get(url).isEmpty())
        assertTrue(composite.get(url).any { it.name == "session" && it.value == "xyz" })
    }

    @Test
    fun get_returnsCookiesFromBothStorages() = runTest {
        // Arrange
        val (composite, _) = createComposite()

        // Act
        composite.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "route123"))
        composite.addCookie(url, Cookie(name = "session", value = "sess456"))

        // Assert
        val cookies = composite.get(url)
        assertTrue(cookies.any { it.name == ZETA_ROUTE_COOKIE && it.value == "route123" })
        assertTrue(cookies.any { it.name == "session" && it.value == "sess456" })
    }

    @Test
    fun addCookie_zetaRoute_notStoredInDefaultStorage() = runTest {
        // Arrange
        val (composite, _) = createComposite()

        // Act
        composite.addCookie(url, Cookie(name = ZETA_ROUTE_COOKIE, value = "abc123"))

        // Assert
        val cookies = composite.get(url)
        assertEquals(1, cookies.filter { it.name == ZETA_ROUTE_COOKIE }.size)
    }
}
