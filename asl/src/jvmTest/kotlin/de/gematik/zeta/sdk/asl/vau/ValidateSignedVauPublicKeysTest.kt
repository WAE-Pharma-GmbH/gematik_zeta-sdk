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

package de.gematik.zeta.sdk.asl.vau

import de.gematik.zeta.sdk.asl.AslCertDataApi
import de.gematik.zeta.sdk.asl.AslStorageImpl
import de.gematik.zeta.sdk.asl.CertData
import de.gematik.zeta.sdk.asl.SignedVauPublicKeys
import de.gematik.zeta.sdk.asl.VauKeys
import de.gematik.zeta.sdk.asl.cbor
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.EcdhSigner
import de.gematik.zeta.sdk.crypto.X509CertValidator
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class ValidateSignedVauPublicKeysTest {
    private val requiredOid = "1.2.276.0.76.4.261"
    private val now = 1_700_000_000L
    private val oneDay = SECONDS_PER_DAY.toLong()
    private val thirtyDays = MAX_KEY_LIFETIME_SECONDS.toLong()

    private fun defaultVauKeys(
        crv: String = "P-256",
        x: ByteArray = ByteArray(32),
        y: ByteArray = ByteArray(32),
        mlKem: ByteArray = ByteArray(1184),
        expiresAt: Long = now + thirtyDays,
        issuedAt: Long = now,
    ) = VauKeys(
        ecdhPublicKey = EcPointP256(crv = crv, x = x, y = y),
        mlKemPublicKey = mlKem,
        expiresAt = expiresAt,
        issuedAt = issuedAt,
        comment = "",
    )

    private fun buildSigned(
        vauKeys: VauKeys = defaultVauKeys(),
        certificateHash: ByteArray = ByteArray(32) { 1 },
        ocspResponse: ByteArray = ByteArray(0),
        es256Signature: ByteArray = ByteArray(64) { 2 },
    ) = SignedVauPublicKeys(
        signedPublicKeys = cbor.encodeToByteArray(VauKeys.serializer(), vauKeys),
        certificateHash = certificateHash,
        certificateDescriptionVersion = 1,
        ocspResponse = ocspResponse,
        es256Signature = es256Signature,
    )

    private fun defaultCertData(
        cert: ByteArray = ByteArray(10) { 1 },
        ca: ByteArray = ByteArray(10) { 2 },
        rcaChain: List<ByteArray> = emptyList(),
    ) = CertData(cert = cert, ca = ca, rcaChain = rcaChain)

    private fun buildValidator(
        sanDnsNames: List<String> = listOf("leaf.example.com"),
        professionOids: List<String> = listOf(requiredOid),
        publicKey: ByteArray = ByteArray(64),
    ): X509CertValidator = mockk<X509CertValidator>(relaxed = true).also {
        every { it.getSanDnsNames(any()) } returns sanDnsNames
        every { it.getProfessionOids(any()) } returns professionOids
        every { it.getPublicKey(any()) } returns publicKey
    }

    private fun buildSigner(verifyResult: Boolean = true): EcdhSigner =
        mockk<EcdhSigner>().also {
            every { it.verify(any(), any(), any()) } returns verifyResult
        }

    private fun buildBundle(
        certData: CertData = defaultCertData(),
        certChainValidator: X509CertValidator = buildValidator(),
        ecdhSigner: EcdhSigner = buildSigner(),
        tiTrustAnchors: List<ByteArray> = listOf(ByteArray(10)),
        host: String = "leaf.example.com",
    ): CertValidationBundle {
        val fetcher = mockk<AslCertDataApi>()
        every { runBlocking { fetcher.fetch(any(), any()) } } returns certData
        val revocationChecker: RevocationChecker = mockk(relaxed = true)

        return CertValidationBundle(
            http = HttpContext(
                client = ZetaHttpClient(HttpClient(MockEngine { error("not called") })),
                request = HttpRequestBuilder().apply { url("https://$host/resource") },
            ),
            certDataFetcher = fetcher,
            certChainValidator = certChainValidator,
            ocspValidator = mockk(relaxed = true),
            ecdhSigner = ecdhSigner,
            tiTrustAnchors = tiTrustAnchors,
            certDataCache = AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())), revocationChecker = revocationChecker,
        )
    }

    @Test
    fun validateChain_throwsWhenNoTrustAnchors() {
        val ex = assertFailsWith<IllegalStateException> {
            validateChain(defaultCertData(), buildBundle(tiTrustAnchors = emptyList()))
        }
        assertTrue(ex.message?.contains("no TI trust anchors") == true)
    }

    @Test
    fun validateChain_throwsWhenChainIncomplete() {
        val ex = assertFailsWith<IllegalStateException> {
            validateChain(defaultCertData(ca = ByteArray(0)), buildBundle())
        }
        assertTrue(ex.message?.contains("incomplete chain") == true)
    }

    @Test
    fun validateChain_doesNotThrow_whenCertAndCaPresent() {
        validateChain(defaultCertData(), buildBundle())
    }

    @Test
    fun validateChain_filtersEmptyRcaEntries() {
        validateChain(defaultCertData(rcaChain = listOf(ByteArray(0))), buildBundle())
    }

    @Test
    fun validateChain_passesCorrectChainToValidator() {
        val validator = buildValidator()
        val cert = ByteArray(10) { 1 }
        val ca = ByteArray(10) { 2 }
        validateChain(defaultCertData(cert = cert, ca = ca), buildBundle(certChainValidator = validator))
        verify {
            validator.validateCertChain(
                match { it.size == 2 && it[0].contentEquals(cert) && it[1].contentEquals(ca) },
                any(),
            )
        }
    }

    @Test
    fun validateRoleOid_doesNotThrow_whenOidPresent() {
        validateRoleOid(ByteArray(10), requiredOid, buildValidator(professionOids = listOf(requiredOid)))
    }

    @Test
    fun validateRoleOid_throwsWhenOidMissing() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateRoleOid(ByteArray(10), requiredOid, buildValidator(professionOids = listOf("1.2.3")))
        }
        assertEquals(ex.message?.contains("missing required Role-OID"), true)
        assertEquals(ex.message?.contains(requiredOid), true)
    }

    @Test
    fun validateRoleOid_throwsWhenOidListEmpty() {
        assertFailsWith<IllegalArgumentException> {
            validateRoleOid(ByteArray(10), requiredOid, buildValidator(professionOids = emptyList()))
        }
    }

    @Test
    fun validateSan_doesNotThrow_whenSanMatchesHost() {
        validateSan(ByteArray(10), "leaf.example.com", buildValidator(sanDnsNames = listOf("leaf.example.com")))
    }

    @Test
    fun validateSan_doesNotThrow_whenWildcardMatches() {
        validateSan(ByteArray(10), "leaf.example.com", buildValidator(sanDnsNames = listOf("*.example.com")))
    }

    @Test
    fun validateSan_throwsWhenNoSanMatches() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateSan(ByteArray(10), "leaf.example.com", buildValidator(sanDnsNames = listOf("other.example.com")))
        }
        assertTrue(ex.message?.contains("SAN does not match") == true)
    }

    @Test
    fun validateSan_throwsWhenSanListEmpty() {
        assertFailsWith<IllegalArgumentException> {
            validateSan(ByteArray(10), "leaf.example.com", buildValidator(sanDnsNames = emptyList()))
        }
    }

    @Test
    fun validateCertificate_doesNotThrow_whenValid() {
        validateCertificate(defaultCertData(), "leaf.example.com", buildBundle())
    }

    @Test
    fun validateCertificate_throwsWhenSanMismatch() {
        val bundle = buildBundle(certChainValidator = buildValidator(sanDnsNames = listOf("other.example.com")))
        assertFailsWith<IllegalArgumentException> {
            validateCertificate(defaultCertData(), "leaf.example.com", bundle)
        }
    }

    @Test
    fun validateCertificate_throwsWhenCheckValidityThrows() {
        val validator = buildValidator()
        every { validator.checkValidity(any()) } throws IllegalArgumentException("expired")
        assertFailsWith<IllegalArgumentException> {
            validateCertificate(defaultCertData(), "leaf.example.com", buildBundle(certChainValidator = validator))
        }
    }

    @Test
    fun validateSignature_doesNotThrow_whenSignatureValid() {
        validateSignature(buildSigned(), ByteArray(10), buildBundle(ecdhSigner = buildSigner(true)))
    }

    @Test
    fun validateSignature_throwsWhenSignatureInvalid() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateSignature(buildSigned(), ByteArray(10), buildBundle(ecdhSigner = buildSigner(false)))
        }
        assertEquals(ex.message?.contains("ES256 signature invalid"), true)
    }

    @Test
    fun validateSignature_passesSignedPublicKeysAsData() {
        val signedPublicKeys = ByteArray(32) { 0xCC.toByte() }
        val signed = buildSigned().copy(signedPublicKeys = signedPublicKeys)
        val signer = buildSigner()
        validateSignature(signed, ByteArray(10), buildBundle(ecdhSigner = signer))
        verify { signer.verify(any(), signedPublicKeys, any()) }
    }

    @Test
    fun decodeAndValidateVauKeys_returnsVauKeys_whenValid() {
        val result = decodeAndValidateVauKeys(buildSigned(), FixedClock(now - oneDay))
        assertEquals("P-256", result.ecdhPublicKey.crv)
    }

    @Test
    fun decodeAndValidateVauKeys_throwsWhenExpired() {
        val signed = buildSigned(vauKeys = defaultVauKeys(expiresAt = now - 1))
        assertFailsWith<IllegalArgumentException> {
            decodeAndValidateVauKeys(signed, FixedClock(now))
        }
    }

    @Test
    fun decodeAndValidateVauKeys_throwsWhenCurveNotP256() {
        val signed = buildSigned(vauKeys = defaultVauKeys(crv = "P-384"))
        val ex = assertFailsWith<IllegalArgumentException> {
            decodeAndValidateVauKeys(signed, FixedClock(now - oneDay))
        }
        assertEquals(ex.message?.contains("P-256"), true)
    }

    @Test
    fun decodeAndValidateVauKeys_throwsWhenMlKemWrongSize() {
        val signed = buildSigned(vauKeys = defaultVauKeys(mlKem = ByteArray(1183)))
        val ex = assertFailsWith<IllegalArgumentException> {
            decodeAndValidateVauKeys(signed, FixedClock(now - oneDay))
        }
        assertEquals(ex.message?.contains("1184"), true)
    }

    @Test
    fun validateSignedVauPublicKeys_succeedsWhenAllValid() = runTest {
        val revocationChecker: RevocationChecker = mockk(relaxed = true)

        coEvery {
            revocationChecker.validate(
                stapledOcspResponse = any(),
                certDer = any(),
                issuerDer = any(),
            )
        } returns Unit

        validateSignedVauPublicKeys(
            signed = buildSigned(),
            validation = buildBundle(),
            clock = FixedClock(now - oneDay),
            requiredRoleOid = requiredOid,

        )
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenNoTrustAnchors() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            validateSignedVauPublicKeys(
                signed = buildSigned(),
                validation = buildBundle(tiTrustAnchors = emptyList()),
                clock = FixedClock(now - oneDay),
                requiredRoleOid = requiredOid,
            )
        }
        assertEquals(ex.message?.contains("no TI trust anchors"), true)
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenRoleOidMissing() = runTest {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = buildSigned(),
                validation = buildBundle(certChainValidator = buildValidator(professionOids = listOf("1.2.3"))),
                clock = FixedClock(now - oneDay),
                requiredRoleOid = requiredOid,
            )
        }
        assertEquals(ex.message?.contains("missing required Role-OID"), true)
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenSignatureInvalid() = runTest {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = buildSigned(),
                validation = buildBundle(ecdhSigner = buildSigner(false)),
                clock = FixedClock(now - oneDay),
                requiredRoleOid = requiredOid,
            )
        }
        assertEquals(ex.message?.contains("ES256 signature invalid"), true)
    }
}
