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

import de.gematik.zeta.sdk.crypto.OcspRequestData
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.cacheKeyFor
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class RevocationValidatorTest {
    private val nowEpoch = Clock.System.now().epochSeconds
    private val certDer = byteArrayOf(1, 2, 3)
    private val issuerDer = byteArrayOf(4, 5, 6)
    private val ocspResponseBytes = byteArrayOf(7, 8, 9)

    @Test
    fun validate_fallsBackToCrl_whenDirectOcspFails() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
        )
        val checker = buildChecker(handler = handler)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertTrue(handler.crlValidationCalled)
    }

    @Test
    fun validate_fails_whenCrlFetchFails() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = "https://crl.example.com",
        )
        val checker = buildChecker(
            handler = handler,
            httpClient = mockHttpClient(throws = true),
        )

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_fails_whenStapledOcspTooOld() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (25 * 3600),
            nextUpdate = null,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalArgumentException> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_acceptsStaleStaple_whenNextUpdateInFuture() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (3 * 24 * 3600),
            nextUpdate = nowEpoch + (4 * 24 * 3600),
        )
        val checker = buildChecker(handler = handler)

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertTrue(handler.ocspValidationCalled)
    }

    @Test
    fun validate_rejectsStaple_whenNextUpdateExpired() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (8 * 24 * 3600),
            nextUpdate = nowEpoch - 3600,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalArgumentException> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_fallsBackTo24Hours_whenNextUpdateAbsent() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - 3600,
            nextUpdate = null,
        )
        val checker = buildChecker(handler = handler)

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertTrue(handler.ocspValidationCalled)
    }

    @Test
    fun validate_rejects_whenNextUpdateAbsentAndOlderThan24Hours() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (25 * 3600),
            nextUpdate = null,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalArgumentException> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_usesStapledOcsp_whenAvailable() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
        )
        val checker = buildChecker(handler = handler)

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertTrue(handler.ocspValidationCalled)
        assertTrue(!handler.ocspRequestPrepared)
        assertTrue(!handler.crlValidationCalled)
    }

    @Test
    fun validate_fails_whenStapledOcspValidationFails() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
            validateThrows = Exception("Certificate revoked"),
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<Exception> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_succeedsViaDirectOcsp_whenNoStapling() = runTest {
        val directOcspResponse = ByteArray(128) { 1 }

        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
            crlUrl = null,
        )

        val checker = buildChecker(
            handler = handler,
            httpClient = mockHttpClient(
                responseBytes = directOcspResponse,
            ),
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertTrue(
            handler.ocspRequestPrepared,
            "Expected a direct OCSP request to be prepared",
        )
        assertTrue(
            handler.ocspValidationCalled,
            "Expected the direct OCSP response to be validated",
        )
        assertTrue(
            !handler.crlValidationCalled,
            "CRL validation should not be called",
        )
    }

    @Test
    fun validate_fails_whenNoCrlUrlInCertificate() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = null,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_fails_whenCrlValidationFails() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = "https://crl.example.com",
            crlValidateThrows = Exception("CRL invalid"),
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_skipsCheck_forTestCertificates() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = null,
        )
        val checker = buildChecker(
            handler = handler,
            allowSkipForTestCertificates = true,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )
    }

    @Test
    fun validate_errorMessage_containsAllFailureReasons() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = "https://crl.example.com",
            crlValidateThrows = Exception("CRL failed"),
        )
        val checker = buildChecker(handler = handler)

        val exception = assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }

        assertTrue(exception.message.orEmpty().contains("OCSP failed"))
        assertTrue(exception.message.orEmpty().contains("CRL failed"))
    }

    @Test
    fun validate_respectsCustomMaxOcspAge() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - 3600,
            nextUpdate = null,
        )

        buildChecker(
            handler = handler,
            maxOcspAgeSeconds = 2 * 3600L,
        ).validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val strictChecker = buildChecker(
            handler = handler,
            maxOcspAgeSeconds = 1800L,
        )

        assertFailsWith<IllegalArgumentException> {
            strictChecker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validateChain_validatesEachLinkInChain() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
            crlUrl = null,
        )
        val checker = buildChecker(
            handler = handler,
            httpClient = mockHttpClient(responseBytes = ByteArray(128) { 1 }),
        )

        val leafDer = byteArrayOf(1, 2, 3)
        val intermediateDer = byteArrayOf(4, 5, 6)
        val rootDer = byteArrayOf(7, 8, 9)

        checker.validateChain(
            stapledOcspResponse = null,
            chain = listOf(leafDer, intermediateDer, rootDer),
        )

        assertTrue(handler.ocspValidationCalled)
    }

    @Test
    fun validateChain_excludesLastCertificate_asRoot() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
            crlUrl = null,
        )
        val checker = buildChecker(
            handler = handler,
            httpClient = mockHttpClient(responseBytes = ByteArray(128) { 1 }),
        )

        val leafDer = byteArrayOf(1, 2, 3)
        val rootDer = byteArrayOf(7, 8, 9)

        checker.validateChain(
            stapledOcspResponse = null,
            chain = listOf(leafDer, rootDer),
        )

        assertTrue(handler.ocspValidationCalled)
    }

    @Test
    fun validateChain_appliesStaple_onlyToLeaf() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
        )
        val checker = buildChecker(handler = handler)

        val leafDer = byteArrayOf(1, 2, 3)
        val intermediateDer = byteArrayOf(4, 5, 6)
        val rootDer = byteArrayOf(7, 8, 9)

        checker.validateChain(
            stapledOcspResponse = ocspResponseBytes,
            chain = listOf(leafDer, intermediateDer, rootDer),
        )

        assertTrue(handler.ocspValidationCalled)
    }

    @Test
    fun validateChain_fails_whenAnyLinkFailsRevocation() = runTest {
        val handler = FakeRevocationHandler(
            validateThrows = Exception("OCSP failed"),
            crlUrl = null,
        )
        val checker = buildChecker(handler = handler)

        val leafDer = byteArrayOf(1, 2, 3)
        val intermediateDer = byteArrayOf(4, 5, 6)
        val rootDer = byteArrayOf(7, 8, 9)

        assertFailsWith<IllegalStateException> {
            checker.validateChain(
                stapledOcspResponse = null,
                chain = listOf(leafDer, intermediateDer, rootDer),
            )
        }
    }

    @Test
    fun validateChain_fails_whenChainHasFewerThanTwoCertificates() = runTest {
        val handler = FakeRevocationHandler()
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalArgumentException> {
            checker.validateChain(
                stapledOcspResponse = null,
                chain = listOf(byteArrayOf(1, 2, 3)),
            )
        }
    }

    @Test
    fun validateChain_cachesEachLinkIndependently() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3600,
        )
        val storage = InMemoryStorage()
        val checker = RevocationChecker(
            storage = RevocationStorage(
                storage = storage,
                resourceScope = ResourceScope("https://resource.example.com", emptyList()),
            ),
            httpClient = mockHttpClient(),
            handler = handler,
        )

        val leafDer = byteArrayOf(1, 2, 3)
        val intermediateDer = byteArrayOf(4, 5, 6)
        val rootDer = byteArrayOf(7, 8, 9)

        checker.validateChain(
            stapledOcspResponse = ocspResponseBytes,
            chain = listOf(leafDer, intermediateDer, rootDer),
        )

        checker.validateChain(
            stapledOcspResponse = ocspResponseBytes,
            chain = listOf(leafDer, intermediateDer, rootDer),
        )
    }

    @Test
    fun validate_cachesUntilNextUpdate_whenNextUpdateExceedsMinimumCacheDuration() =
        runTest {
            val beforeValidation = Clock.System.now().epochSeconds
            val nextUpdate = beforeValidation + 7_200L
            val minimumCacheDuration = 3_600L

            val handler = FakeRevocationHandler(
                thisUpdate = beforeValidation,
                nextUpdate = nextUpdate,
                crlUrl = null,
            )

            val sdkStorage = InMemoryStorage()
            val resourceScope = ResourceScope(
                "https://resource.example.com",
                emptyList(),
            )
            val revocationStorage = buildRevocationStorage(
                storage = sdkStorage,
                resourceScope = resourceScope,
            )

            val directOcspResponse = ByteArray(128) { 1 }

            val checker = buildChecker(
                handler = handler,
                httpClient = mockHttpClient(
                    responseBytes = directOcspResponse,
                ),
                minOcspCacheDurationSeconds = minimumCacheDuration,
                storage = sdkStorage,
                resourceScope = resourceScope,
            )

            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )

            val cached = revocationStorage.getOcsp(
                cacheKeyFor(certDer, issuerDer),
            )

            assertNotNull(cached)
            assertEquals(nextUpdate, cached.expiresAtEpochSeconds)
            assertContentEquals(
                directOcspResponse,
                cached.responseDer,
            )
        }

    @Test
    fun validate_usesOneHourMinimumOcspCacheDurationByDefault() = runTest {
        val expectedDefaultMinimum = 3_600L
        val beforeValidation = Clock.System.now().epochSeconds
        val nextUpdate = beforeValidation + 60L

        val handler = FakeRevocationHandler(
            thisUpdate = beforeValidation,
            nextUpdate = nextUpdate,
            crlUrl = null,
        )

        val sdkStorage = InMemoryStorage()
        val resourceScope = ResourceScope(
            "https://resource.example.com",
            emptyList(),
        )
        val revocationStorage = buildRevocationStorage(
            storage = sdkStorage,
            resourceScope = resourceScope,
        )

        val checker = RevocationChecker(
            storage = revocationStorage,
            httpClient = mockHttpClient(
                responseBytes = ByteArray(128) { 1 },
            ),
            handler = handler,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val afterValidation = Clock.System.now().epochSeconds

        val cached = revocationStorage.getOcsp(
            cacheKeyFor(certDer, issuerDer),
        )

        assertNotNull(cached)

        assertTrue(
            cached.expiresAtEpochSeconds in
                (beforeValidation + expectedDefaultMinimum)..(afterValidation + expectedDefaultMinimum),
            "Expected default minimum cache duration of one hour, " +
                "but expiry was ${cached.expiresAtEpochSeconds}",
        )
    }

    @Test
    fun validate_cachesUntilThisUpdatePlusMaxAge_whenNextUpdateIsAbsent() =
        runTest {
            val maxAge = 24 * 3_600L
            val thisUpdate = Clock.System.now().epochSeconds - 3_600L

            val handler = FakeRevocationHandler(
                thisUpdate = thisUpdate,
                nextUpdate = null,
                crlUrl = null,
            )

            val sdkStorage = InMemoryStorage()
            val resourceScope = ResourceScope(
                "https://resource.example.com",
                emptyList(),
            )
            val revocationStorage = buildRevocationStorage(
                storage = sdkStorage,
                resourceScope = resourceScope,
            )

            val checker = buildChecker(
                handler = handler,
                httpClient = mockHttpClient(
                    responseBytes = ByteArray(128) { 1 },
                ),
                maxOcspAgeSeconds = maxAge,
                storage = sdkStorage,
                resourceScope = resourceScope,
            )

            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )

            val cached = revocationStorage.getOcsp(
                cacheKeyFor(certDer, issuerDer),
            )

            assertNotNull(cached)
            assertEquals(
                thisUpdate + maxAge,
                cached.expiresAtEpochSeconds,
            )
        }

    private fun buildChecker(
        handler: RevocationHandler,
        httpClient: HttpClient = mockHttpClient(),
        maxOcspAgeSeconds: Long = 24 * 3600L,
        minOcspCacheDurationSeconds: Long = 3_600L,
        allowSkipForTestCertificates: Boolean = false,
        storage: SdkStorage = InMemoryStorage(),
        resourceScope: ResourceScope = ResourceScope(
            "https://resource.example.com",
            emptyList(),
        ),
    ): RevocationChecker {
        return RevocationChecker(
            storage = RevocationStorage(
                storage = storage,
                resourceScope = resourceScope,
            ),
            httpClient = httpClient,
            handler = handler,
            maxOcspAgeSeconds = maxOcspAgeSeconds,
            minOcspCacheDurationSeconds = minOcspCacheDurationSeconds,
            allowSkipForTestCertificates = allowSkipForTestCertificates,
        )
    }

    private fun buildRevocationStorage(
        storage: SdkStorage,
        resourceScope: ResourceScope,
    ): RevocationStorage =
        RevocationStorage(
            storage = storage,
            resourceScope = resourceScope,
        )

    private class FakeRevocationHandler(
        private val thisUpdate: Long =
            Clock.System.now().epochSeconds,
        private val nextUpdate: Long? = null,
        private val validateThrows: Exception? = null,
        private val crlUrl: String? =
            "https://crl.example.com",
        private val crlValidateThrows: Exception? = null,
        private val crlNextUpdate: Long? = null,
    ) : RevocationHandler {
        var ocspRequestPrepared: Boolean = false
            private set

        var ocspValidationCalled: Boolean = false
            private set

        var crlValidationCalled: Boolean = false
            private set

        override fun getThisUpdateEpochSeconds(
            ocspResponseDer: ByteArray,
        ): Long = thisUpdate

        override fun getNextUpdateEpochSeconds(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ): Long? = nextUpdate

        override fun validate(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ) {
            ocspValidationCalled = true
            validateThrows?.let { throw it }
        }

        override suspend fun prepareOcspRequest(
            certDer: ByteArray,
            issuerDer: ByteArray,
        ): OcspRequestData {
            ocspRequestPrepared = true

            return OcspRequestData(
                "https://ocsp.example.com",
                byteArrayOf(1),
            )
        }

        override fun extractCrlUrl(
            certDer: ByteArray,
        ): String? = crlUrl

        override fun validateCrl(
            crlDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ) {
            crlValidationCalled = true
            crlValidateThrows?.let { throw it }
        }

        override fun getCrlNextUpdateEpochSeconds(
            crlDer: ByteArray,
        ): Long? = crlNextUpdate
    }

    private fun mockHttpClient(
        responseBytes: ByteArray =
            byteArrayOf(10, 11, 12),
        throws: Boolean = false,
    ): HttpClient {
        val engine = MockEngine { request ->
            if (throws) {
                error("Fetch failed for ${request.url}")
            }

            respond(
                content = responseBytes,
                status = HttpStatusCode.OK,
            )
        }

        return HttpClient(engine)
    }
}
