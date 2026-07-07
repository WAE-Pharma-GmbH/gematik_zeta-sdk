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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AslStorageImplTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val resourceScope = ResourceScope("https://api.example.com/resource", listOf("scope-a"))

    private fun buildSut(
        storage: FakeSdkStorage = FakeSdkStorage(),
        scope: ResourceScope = resourceScope,
    ): Pair<AslStorageImpl, FakeSdkStorage> =
        AslStorageImpl(storage, scope, json) to storage

    @Test
    fun saveSession_storesSerializedSession_validSession() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val session = buildSession(requestCounter = 42L, encCounter = 13L)

        // Act
        sut.saveSession(session)

        // Assert
        val stored = storage.getAll()
        val sessionKey = stored.keys.first { it.startsWith(AslStorageImpl.PREFIX) }
        val storedJson = stored[sessionKey] ?: error("Session not found in storage for key: $sessionKey")
        val decoded = json.decodeFromString<EstablishedSession>(storedJson)
        assertEquals(42L, decoded.requestCounter)
        assertEquals(13L, decoded.encCounter)
    }

    @Test
    fun saveSession_usesStorageKey_forSessionStorage() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val session = buildSession()

        // Act
        sut.saveSession(session)

        // Assert
        val sessionKeys = storage.getAll().keys.filter { it.startsWith(AslStorageImpl.PREFIX) }
        assertEquals(1, sessionKeys.size)
        assertFalse(sessionKeys.first().contains("example.com"))
    }

    @Test
    fun getCurrentSession_returnsSession_validStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveSession(buildSession(requestCounter = 99L, encCounter = 77L))

        // Act
        val result = sut.getCurrentSession()

        // Assert
        assertEquals(99L, result!!.requestCounter)
        assertEquals(77L, result.encCounter)
    }

    @Test
    fun getCurrentSession_returnsNull_noStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getCurrentSession()

        // Assert
        assertNull(result)
    }

    @Test
    fun getCurrentSession_throwsException_invalidJson() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        val (sut, _) = buildSut(storage)
        sut.saveSession(buildSession())
        val sessionKey = storage.getAll().keys.first { it.startsWith(AslStorageImpl.PREFIX) }
        storage.put(sessionKey, "{invalid json}")

        // Act & Assert
        assertFailsWith<Exception> {
            sut.getCurrentSession()
        }
    }

    @Test
    fun clear_removesSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveSession(buildSession())

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getCurrentSession())
    }

    @Test
    fun clear_removesAllSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        sut.saveSession(buildSession(requestCounter = 1L))

        // Act
        sut.clear()

        // Assert
        val sessionKeys = storage.getAll().keys.filter { it.startsWith(AslStorageImpl.PREFIX) }
        assertTrue(sessionKeys.isEmpty())
    }

    @Test
    fun clear_handlesEmpty_noSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.clear()

        // Assert
        assertTrue(storage.getAll().isEmpty())
    }

    @Test
    fun clear_removesOnlyAslSessions_mixedStorage() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        storage.put("other_key_1", "value1")
        storage.put("other_key_2", "value2")
        val (sut, _) = buildSut(storage)
        sut.saveSession(buildSession())

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        assertEquals(2, stored.size)
        assertTrue(stored.containsKey("other_key_1"))
        assertTrue(stored.containsKey("other_key_2"))
    }

    private class FakeSdkStorage : SdkStorage {
        private val store = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = store[key]
        override suspend fun put(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
        override suspend fun clear() { store.clear() }
        fun getAll(): Map<String, String> = store.toMap()
    }
}

private fun assertTrue(condition: Boolean) {
    kotlin.test.assertTrue(condition)
}

private fun assertFalse(condition: Boolean) {
    kotlin.test.assertFalse(condition)
}

private fun buildSession(
    keyId: ByteArray = ByteArray(32) { it.toByte() },
    requestCounter: Long = 0L,
    encCounter: Long = 0L,
): EstablishedSession = EstablishedSession(
    keyId = keyId,
    c2sAppDataKey = ByteArray(32) { 1 },
    s2cAppDataKey = ByteArray(32) { 2 },
    requestCounter = requestCounter,
    encCounter = encCounter,
)
