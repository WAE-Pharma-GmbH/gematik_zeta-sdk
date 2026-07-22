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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class RevocationStorageTest {

    private val now = Clock.System.now().epochSeconds
    private val farFuture = now + 3600
    private val past = now - 3600

    private fun buildStorage(
        scope: ResourceScope = ResourceScope("resource-a", emptyList()),
        backing: InMemoryStorage = InMemoryStorage(),
    ): RevocationStorage = RevocationStorage(storage = backing, resourceScope = scope)

    @Test
    fun get_returnsNull_whenNothingStored() = runTest {
        val cache = buildStorage()
        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun set_thenGet_returnsSameResponse() = runTest {
        val cache = buildStorage()
        val response = CachedOcspResponse(
            responseDer = byteArrayOf(1, 2, 3, 4, 5),
            expiresAtEpochSeconds = farFuture,
        )

        cache.setOcsp("cert-123", response)
        val result = cache.getOcsp("cert-123")

        assertNotNull(result)
        assertContentEquals(response.responseDer, result.responseDer)
        assertEquals(response.expiresAtEpochSeconds, result.expiresAtEpochSeconds)
    }

    @Test
    fun set_overwritesPreviousValue_forSameKey() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), farFuture))
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(9, 9), farFuture))

        val result = cache.getOcsp("cert-123")

        assertNotNull(result)
        assertContentEquals(byteArrayOf(9, 9), result.responseDer)
    }

    @Test
    fun get_distinguishesDifferentCacheKeys() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-A", CachedOcspResponse(byteArrayOf(1), farFuture))
        cache.setOcsp("cert-B", CachedOcspResponse(byteArrayOf(2), farFuture))

        assertContentEquals(byteArrayOf(1), cache.getOcsp("cert-A")?.responseDer)
        assertContentEquals(byteArrayOf(2), cache.getOcsp("cert-B")?.responseDer)
    }

    @Test
    fun get_returnsNull_whenEntryIsExpired() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), expiresAtEpochSeconds = past))

        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun get_treatsExpiryEqualToNow_asExpired() = runTest {
        val cache = buildStorage()
        val nowAtCall = Clock.System.now().epochSeconds
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), expiresAtEpochSeconds = nowAtCall))

        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun get_removesExpiredEntry_soSubsequentGetsStayNull() = runTest {
        val backing = InMemoryStorage()
        val cache = buildStorage(backing = backing)
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), expiresAtEpochSeconds = past))

        cache.getOcsp("cert-123")
        val secondCache = buildStorage(backing = backing)
        assertNull(secondCache.getOcsp("cert-123"))
    }

    @Test
    fun get_returnsValidEntry_whenNotYetExpired() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(7), expiresAtEpochSeconds = farFuture))

        assertNotNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun clear_removesEntry() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), farFuture))

        cache.clearOcsp("cert-123")

        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun clear_onMissingKey_doesNotThrow() = runTest {
        val cache = buildStorage()
        cache.clearOcsp("never-existed")
    }

    @Test
    fun differentResourceScopes_doNotShareCacheEntries() = runTest {
        val backing = InMemoryStorage()
        val cacheA = buildStorage(scope = ResourceScope("resource-a", emptyList()), backing = backing)
        val cacheB = buildStorage(scope = ResourceScope("resource-b", emptyList()), backing = backing)

        cacheA.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), farFuture))

        assertNotNull(cacheA.getOcsp("cert-123"))
        assertNull(cacheB.getOcsp("cert-123"))
    }

    @Test
    fun sameResourceScope_sharesCacheEntries_acrossInstances() = runTest {
        val backing = InMemoryStorage()
        val scope = ResourceScope("resource-a", emptyList())
        val cacheA = buildStorage(scope = scope, backing = backing)
        val cacheB = buildStorage(scope = scope, backing = backing)

        cacheA.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1, 2), farFuture))

        val result = cacheB.getOcsp("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(1, 2), result.responseDer)
    }

    @Test
    fun set_thenGet_handlesEmptyResponseDer() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(), farFuture))

        val result = cache.getOcsp("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(), result.responseDer)
    }

    @Test
    fun serializableOcspResponse_roundTripsViaBase64() {
        val original = CachedOcspResponse(
            responseDer = byteArrayOf(0, -1, 127, -128, 5),
            expiresAtEpochSeconds = farFuture,
        )

        val serialized = SerializableOcspResponse.from(original)
        val restored = serialized.toCached()

        assertContentEquals(original.responseDer, restored.responseDer)
        assertEquals(original.expiresAtEpochSeconds, restored.expiresAtEpochSeconds)
    }

    @Test
    fun getCrl_returnsNull_whenNothingStored() = runTest {
        val cache = buildStorage()
        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun setCrl_thenGetCrl_returnsSameResponse() = runTest {
        val cache = buildStorage()
        val response = CachedCrlResponse(
            crlDer = byteArrayOf(1, 2, 3, 4, 5),
            expiresAtEpochSeconds = farFuture,
        )

        cache.setCrl("cert-123", response)
        val result = cache.getCrl("cert-123")

        assertNotNull(result)
        assertContentEquals(response.crlDer, result.crlDer)
        assertEquals(response.expiresAtEpochSeconds, result.expiresAtEpochSeconds)
    }

    @Test
    fun setCrl_overwritesPreviousValue_forSameKey() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), farFuture))
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(9, 9), farFuture))

        val result = cache.getCrl("cert-123")

        assertNotNull(result)
        assertContentEquals(byteArrayOf(9, 9), result.crlDer)
    }

    @Test
    fun getCrl_distinguishesDifferentCacheKeys() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-A", CachedCrlResponse(byteArrayOf(1), farFuture))
        cache.setCrl("cert-B", CachedCrlResponse(byteArrayOf(2), farFuture))

        assertContentEquals(byteArrayOf(1), cache.getCrl("cert-A")?.crlDer)
        assertContentEquals(byteArrayOf(2), cache.getCrl("cert-B")?.crlDer)
    }

    @Test
    fun getCrl_returnsNull_whenEntryIsExpired() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), expiresAtEpochSeconds = past))

        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun getCrl_treatsExpiryEqualToNow_asExpired() = runTest {
        val cache = buildStorage()
        val nowAtCall = Clock.System.now().epochSeconds
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), expiresAtEpochSeconds = nowAtCall))

        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun getCrl_removesExpiredEntry_soSubsequentGetsStayNull() = runTest {
        val backing = InMemoryStorage()
        val cache = buildStorage(backing = backing)
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), expiresAtEpochSeconds = past))

        cache.getCrl("cert-123")
        val secondCache = buildStorage(backing = backing)
        assertNull(secondCache.getCrl("cert-123"))
    }

    @Test
    fun getCrl_returnsValidEntry_whenNotYetExpired() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(7), expiresAtEpochSeconds = farFuture))

        assertNotNull(cache.getCrl("cert-123"))
    }

    @Test
    fun clearCrl_removesEntry() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), farFuture))

        cache.clearCrl("cert-123")

        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun clearCrl_onMissingKey_doesNotThrow() = runTest {
        val cache = buildStorage()
        cache.clearCrl("never-existed")
    }

    @Test
    fun differentResourceScopes_doNotShareCrlCacheEntries() = runTest {
        val backing = InMemoryStorage()
        val cacheA = buildStorage(scope = ResourceScope("resource-a", emptyList()), backing = backing)
        val cacheB = buildStorage(scope = ResourceScope("resource-b", emptyList()), backing = backing)

        cacheA.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1), farFuture))

        assertNotNull(cacheA.getCrl("cert-123"))
        assertNull(cacheB.getCrl("cert-123"))
    }

    @Test
    fun sameResourceScope_sharesCrlCacheEntries_acrossInstances() = runTest {
        val backing = InMemoryStorage()
        val scope = ResourceScope("resource-a", emptyList())
        val cacheA = buildStorage(scope = scope, backing = backing)
        val cacheB = buildStorage(scope = scope, backing = backing)

        cacheA.setCrl("cert-123", CachedCrlResponse(byteArrayOf(1, 2), farFuture))

        val result = cacheB.getCrl("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(1, 2), result.crlDer)
    }

    @Test
    fun setCrl_thenGetCrl_handlesEmptyResponseDer() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(), farFuture))

        val result = cache.getCrl("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(), result.crlDer)
    }

    @Test
    fun serializableCrlResponse_roundTripsViaBase64() {
        val original = CachedCrlResponse(
            crlDer = byteArrayOf(0, -1, 127, -128, 5),
            expiresAtEpochSeconds = farFuture,
        )

        val serialized = SerializableCrlResponse.from(original)
        val restored = serialized.toCached()

        assertContentEquals(original.crlDer, restored.crlDer)
        assertEquals(original.expiresAtEpochSeconds, restored.expiresAtEpochSeconds)
    }

    @Test
    fun clear_removesBothOcspAndCrlEntries() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", CachedOcspResponse(byteArrayOf(1), farFuture))
        cache.setCrl("cert-123", CachedCrlResponse(byteArrayOf(2), farFuture))

        cache.clear()

        assertNull(cache.getOcsp("cert-123"))
        assertNull(cache.getCrl("cert-123"))
    }
}
