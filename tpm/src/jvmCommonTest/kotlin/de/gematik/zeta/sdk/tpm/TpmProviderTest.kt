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

package de.gematik.zeta.sdk.tpm

import de.gematik.zeta.sdk.crypto.EcdhSigner
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import joseToDerEcdsa
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Suppress("FunctionNaming", "TooManyFunctions")
class TpmProviderTest {
    companion object {
        private const val RESOURCE = "https://resource.example.com/api"
        private const val RESOURCE_2 = "https://other.example.com/api"
        private const val KEYSTORE_P12_FILE = "../test-keystore/test.p12"
        private const val KEYSTORE_ALIAS = "test"
        private const val KEYSTORE_PASS = "pass"
        private const val CERTIFICATE_DATA =
            "MIICBTCCAaygAwIBAgIUb/5wFQ2LA7wGXxGIFsqnN0f5JWEwCgYIKoZIzj0EAwIw" +
                "WDELMAkGA1UEBhMCVVMxDjAMBgNVBAgMBVN0YXRlMQ0wCwYDVQQHDARDaXR5MQww" +
                "CgYDVQQKDANPcmcxDTALBgNVBAsMBFVuaXQxDTALBgNVBAMMBFRlc3QwHhcNMjUx" +
                "MTEwMDgyMzQ1WhcNMzUxMTA4MDgyMzQ1WjBYMQswCQYDVQQGEwJVUzEOMAwGA1UE" +
                "CAwFU3RhdGUxDTALBgNVBAcMBENpdHkxDDAKBgNVBAoMA09yZzENMAsGA1UECwwE" +
                "VW5pdDENMAsGA1UEAwwEVGVzdDBaMBQGByqGSM49AgEGCSskAwMCCAEBBwNCAASG" +
                "g+NX1WZIQWg5rK7E+QeUvNSwL2lK6Vt3q8KnZFcPvTfRdD0dt1stRcY1SIbK0KIF" +
                "gHCCH+xfyfJ2GmAVayTCo1MwUTAdBgNVHQ4EFgQU9DMA0Y5AY0UsRsNfLIrDBaJv" +
                "VfIwHwYDVR0jBBgwFoAU9DMA0Y5AY0UsRsNfLIrDBaJvVfIwDwYDVR0TAQH/BAUw" +
                "AwEB/zAKBggqhkjOPQQDAgNHADBEAiANBYqfUQ/7Y5NccszWbcUtkOW20lPwoDIJ" +
                "3vh9Gmt1bQIgLr2lVBgfNxmIIh5cxYhOyztpShleKO3CmwERyOcxguw="
    }

    private val resourceScope = ResourceScope("https://resource.example.com", listOf("scope-a"))
    private val tpmStorage: TpmStorage = TpmStorageImpl(InMemoryStorage(), resourceScope)
    private val signer = EcdhSigner()
    private val tpmProvider = platformDefaultProvider(tpmStorage)

    @Before
    fun before() {
        Security.addProvider(BouncyCastleProvider())
    }

    private fun createStorage(scope: ResourceScope = resourceScope) =
        TpmStorageImpl(InMemoryStorage(), scope)

    private fun createProvider(
        storage: TpmStorage = createStorage(),
    ) = platformDefaultProvider(storage)

    private fun createProvider(scope: ResourceScope) =
        platformDefaultProvider(createStorage(scope))

    private fun extractPublicKey(cert: ByteArray): ByteArray {
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = factory.generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
        return certificate.publicKey.encoded
    }

    @Test
    fun readSmbCertificate_returnsExpectedBase64_validP12File() = runTest {
        val cert = tpmProvider.readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
        assertEquals(CERTIFICATE_DATA, Base64.encode(cert))
    }

    @Test
    fun signWithSmbKey_producesValidSignature_validP12File() = runTest {
        val data = "some test data"
        val cert = tpmProvider.readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
        val signature = tpmProvider.signWithSmbKey(data.toByteArray(), KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
        assertTrue(signer.verify(extractPublicKey(cert), data.toByteArray(), joseToDerEcdsa(signature)))
    }

    @Test
    fun generateDpopKey_andSignWithDpopKey_producesValidSignature() = runTest {
        val data = "some test data"
        val key = tpmProvider.generateDpopKey()
        val signature = tpmProvider.signWithDpopKey(data.toByteArray(), RESOURCE)
        assertTrue(signer.verify(key.encoded, data.toByteArray(), joseToDerEcdsa(signature)))
    }

    @Test
    fun readSmbCertificate_throwsIOException_wrongPassword() = runTest {
        val error = assertFailsWith<IOException> {
            tpmProvider.readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, "wrong")
        }
        assertEquals("keystore password was incorrect", error.message)
    }

    @Test
    fun signWithSmbKey_throwsIllegalArgumentException_wrongAlias() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            tpmProvider.signWithSmbKey("".toByteArray(), KEYSTORE_P12_FILE, "wrong", KEYSTORE_PASS)
        }
        assertEquals("No key with alias 'wrong'", error.message)
    }

    @Test
    fun saveClientKeys_storesTimestamp_onFirstSave() = runTest {
        tpmStorage.saveClientKeys("some-public-key", "some-private-key")
        val timestamp = tpmStorage.getClientKeyCreatedAt()
        assertNotNull(timestamp)
        assertTrue(Instant.parse(timestamp).epochSeconds > 0)
    }

    @Test
    fun saveClientKeys_updatesTimestamp_onSecondSave() = runTest {
        tpmStorage.saveClientKeys("pub1", "priv1")
        val firstTimestamp = tpmStorage.getClientKeyCreatedAt()
        delay(100)
        tpmStorage.saveClientKeys("pub2", "priv2")
        val secondTimestamp = tpmStorage.getClientKeyCreatedAt()
        assertNotNull(firstTimestamp)
        assertNotNull(secondTimestamp)
        assertTrue(Instant.parse(secondTimestamp) > Instant.parse(firstTimestamp))
    }

    @Test
    fun getClientKeyCreatedAt_returnsNull_whenNoKeysSaved() = runTest {
        assertNull(tpmStorage.getClientKeyCreatedAt())
    }

    @Test
    fun clear_removesAllClientKeyData_afterSave() = runTest {
        tpmStorage.saveClientKeys("pub", "priv")
        assertNotNull(tpmStorage.getClientKeyCreatedAt())
        tpmStorage.clear()
        assertNull(tpmStorage.getClientKeyCreatedAt())
        assertNull(tpmStorage.getClientPublicKey())
        assertNull(tpmStorage.getClientPrivateKey())
    }

    @Test
    fun saveClientKeys_storesCorrectValues_publicAndPrivate() = runTest {
        tpmStorage.saveClientKeys("my-public-key", "my-private-key")
        assertEquals("my-public-key", tpmStorage.getClientPublicKey())
        assertEquals("my-private-key", tpmStorage.getClientPrivateKey())
    }

    @Test
    fun deleteAllDpopKeys_removesAllResources() = runTest {
        tpmStorage.saveDpopKeys("pub-a", "priv-a")
        tpmStorage.saveDpopKeys("pub-b", "priv-b")
        tpmStorage.deleteAllDpopKeys()
        assertNull(tpmStorage.getDpopPublicKey())
        assertNull(tpmStorage.getDpopPublicKey())
    }

    @Test
    fun getOrGenerateClientInstancePublicKey_returnsNonEmptyPublicKey_onFirstCall() = runTest {
        val key = createProvider().getOrGenerateClientInstancePublicKey()
        assertTrue(key.encoded.isNotEmpty())
    }

    @Test
    fun getOrGenerateClientInstancePublicKey_returnsSameKey_onSubsequentCalls() = runTest {
        val provider = createProvider()
        val key1 = provider.getOrGenerateClientInstancePublicKey()
        val key2 = provider.getOrGenerateClientInstancePublicKey()
        assertContentEquals(key1.encoded, key2.encoded)
        assertEquals(key1.jwk.kid, key2.jwk.kid)
    }

    @Test
    fun getOrGenerateClientInstancePublicKey_savesKeysToStorage_onFirstCall() = runTest {
        val storage = createStorage()
        createProvider(storage).getOrGenerateClientInstancePublicKey()
        assertNotNull(storage.getClientPublicKey())
        assertNotNull(storage.getClientPrivateKey())
    }

    @Test
    fun getOrGenerateClientInstancePublicKey_loadsExistingKey_fromStorage() = runTest {
        val storage = createStorage()
        val key1 = createProvider(storage).getOrGenerateClientInstancePublicKey()
        val key2 = createProvider(storage).getOrGenerateClientInstancePublicKey()
        assertContentEquals(key1.encoded, key2.encoded)
    }

    @Test
    fun getOrGenerateClientInstancePublicKey_returnsEcJwk_withCorrectFields() = runTest {
        val key = createProvider().getOrGenerateClientInstancePublicKey()
        assertEquals("EC", key.jwk.kty)
        assertEquals("ES256", key.jwk.alg)
        assertEquals("P-256", key.jwk.crv)
        assertEquals("sig", key.jwk.use)
        assertTrue(key.jwk.kid.isNotEmpty())
    }

    @Test
    fun signWithClientKey_throwsIllegalStateException_whenKeyNotInitialized() = runTest {
        assertFailsWith<IllegalStateException> {
            createProvider().signWithClientKey("data".encodeToByteArray())
        }
    }

    @Test
    fun signWithClientKey_producesValidSignature_afterKeyInitialized() = runTest {
        val provider = createProvider()
        val publicKey = provider.getOrGenerateClientInstancePublicKey()
        val data = "sign me".encodeToByteArray()
        val signature = provider.signWithClientKey(data)
        assertTrue(signer.verify(publicKey.encoded, data, joseToDerEcdsa(signature)))
    }

    @Test
    fun signWithClientKey_producesDifferentSignature_forDifferentInput() = runTest {
        val provider = createProvider()
        provider.getOrGenerateClientInstancePublicKey()
        val sig1 = provider.signWithClientKey("input-a".encodeToByteArray())
        val sig2 = provider.signWithClientKey("input-b".encodeToByteArray())
        assertFalse(sig1.contentEquals(sig2))
    }

    @Test
    fun signWithClientKey_failsVerification_withWrongPublicKey() = runTest {
        val provider = createProvider()
        provider.getOrGenerateClientInstancePublicKey()
        val wrongKey = createProvider().getOrGenerateClientInstancePublicKey()
        val data = "data".encodeToByteArray()
        val signature = provider.signWithClientKey(data)
        assertFalse(signer.verify(wrongKey.encoded, data, joseToDerEcdsa(signature)))
    }

    @Test
    fun generateDpopKey_savesKeyToStorage_onFirstCall() = runTest {
        val storage = createStorage()
        createProvider(storage).generateDpopKey()
        assertNotNull(storage.getDpopPublicKey())
        assertNotNull(storage.getDpopPrivateKey())
    }

    @Test
    fun generateDpopKey_returnsDifferentKeys_forDifferentInstances() = runTest {
        // Arrange
        val provider1 = createProvider(ResourceScope("https://resource1.example.com", listOf("scope-a")))
        val provider2 = createProvider(ResourceScope("https://resource2.example.com", listOf("scope-a")))

        // Act
        val key1 = provider1.generateDpopKey()
        val key2 = provider2.generateDpopKey()

        // Assert
        assertFalse(key1.encoded.contentEquals(key2.encoded))
    }

    @Test
    fun signWithDpopKey_throwsIllegalStateException_whenKeyNotInitialized() = runTest {
        // Arrange & Act & Assert
        assertFailsWith<IllegalStateException> {
            createProvider().signWithDpopKey("data".encodeToByteArray(), RESOURCE)
        }
    }

    @Test
    fun signWithDpopKey_producesValidSignature_afterKeyGenerated() = runTest {
        // Arrange
        val provider = createProvider()
        val publicKey = provider.generateDpopKey()
        val data = "sign me".encodeToByteArray()

        // Act
        val signature = provider.signWithDpopKey(data, RESOURCE)

        // Assert
        assertTrue(signer.verify(publicKey.encoded, data, joseToDerEcdsa(signature)))
    }

    @Test
    fun forget_withResource_doesNotAffect_otherResources() = runTest {
        val provider = createProvider()
        provider.generateDpopKey()
        provider.forget()
        assertFailsWith<IllegalStateException> {
            provider.signWithDpopKey("data".encodeToByteArray(), RESOURCE)
        }
    }

    @Test
    fun forget_withNull_clearsAllDpopKeys() = runTest {
        // Arrange
        val provider = createProvider()
        provider.generateDpopKey()

        // Act
        provider.forget()

        // Assert
        assertFailsWith<IllegalStateException> {
            provider.signWithDpopKey("data".encodeToByteArray(), RESOURCE)
        }
    }

    @Test
    fun forget_withResource_generatesDifferentDpopKey_onNextCall() = runTest {
        // Arrange
        val provider = createProvider()
        val key1 = provider.generateDpopKey()

        // Act
        provider.forget()
        val key2 = provider.generateDpopKey()

        // Assert
        assertFalse(key1.encoded.contentEquals(key2.encoded))
    }

    @Test
    fun generateDpopKey_returnsSameKey_onSubsequentCalls() = runTest {
        // Arrange
        val provider = createProvider()

        // Act
        val key1 = provider.generateDpopKey()
        val key2 = provider.generateDpopKey()

        // Assert
        assertContentEquals(key1.encoded, key2.encoded)
    }

    @Test
    fun saveDpopKeys_isIndependent_fromClientKeys() = runTest {
        // Arrange & Act
        tpmStorage.saveClientKeys("client-pub", "client-priv")
        tpmStorage.saveDpopKeys("dpop-pub", "dpop-priv")

        // Assert
        assertEquals("client-pub", tpmStorage.getClientPublicKey())
        assertEquals("client-priv", tpmStorage.getClientPrivateKey())
        assertEquals("dpop-pub", tpmStorage.getDpopPublicKey())
        assertEquals("dpop-priv", tpmStorage.getDpopPrivateKey())

        @Test
        fun forget_allowsClientKeyRegeneration_fromStorage() = runTest {
            val storage = createStorage()
            val provider = createProvider(storage)
            val key1 = provider.getOrGenerateClientInstancePublicKey()
            provider.forget()
            val key2 = provider.getOrGenerateClientInstancePublicKey()
            assertContentEquals(key1.encoded, key2.encoded)
        }

        @Test
        fun forget_withResource_generatesDifferentDpopKey_onNextCall() = runTest {
            val provider = createProvider()
            val key1 = provider.generateDpopKey()
            provider.forget()
            val key2 = provider.generateDpopKey()
            assertFalse(key1.encoded.contentEquals(key2.encoded))
        }

        @Test
        fun forget_withNull_clearsAllDpopKeys() = runTest {
            val provider = createProvider()
            provider.generateDpopKey()
            provider.generateDpopKey()
            provider.forget()
            assertFailsWith<IllegalStateException> {
                provider.signWithDpopKey("data".encodeToByteArray(), RESOURCE)
            }
            assertFailsWith<IllegalStateException> {
                provider.signWithDpopKey("data".encodeToByteArray(), RESOURCE_2)
            }
        }

        @Test
        fun isHardwareBacked_returnsFalse_forSoftwareProvider() {
            assertFalse(createProvider().isHardwareBacked)
        }

        @Test
        fun randomUuid_returnsNonNullValue_whenCalled() = runTest {
            assertNotNull(createProvider().randomUuid())
        }

        @Test
        fun randomUuid_returnsDifferentValues_onSubsequentCalls() = runTest {
            val provider = createProvider()
            val uuid1 = provider.randomUuid()
            val uuid2 = provider.randomUuid()
            assertNotEquals(uuid1, uuid2)
        }

        @Test
        fun readSmbCertificate_throwsIllegalStateException_whenFileIsEmpty() = runTest {
            assertFailsWith<IllegalStateException> {
                createProvider().readSmbCertificate("", "alias", "pass")
            }
        }

        @Test
        fun readSmbCertificate_returnsNonEmptyBytes_validP12File() = runTest {
            val cert = createProvider().readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
            assertTrue(cert.isNotEmpty())
        }

        @Test
        fun readSmbCertificateFromBytes_throwsIllegalStateException_whenDataIsEmpty() = runTest {
            assertFailsWith<IllegalStateException> {
                createProvider().readSmbCertificateFromBytes(byteArrayOf(), "alias", "pass")
            }
        }

        @Test
        fun readSmbCertificateFromBytes_returnsSameCert_asReadSmbCertificate() = runTest {
            val provider = createProvider()
            val keystoreBytes = File(KEYSTORE_P12_FILE).readBytes()
            val certFromFile = provider.readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
            val certFromBytes = provider.readSmbCertificateFromBytes(keystoreBytes, KEYSTORE_ALIAS, KEYSTORE_PASS)
            assertContentEquals(certFromFile, certFromBytes)
        }

        @Test
        fun readSmbCertificateFromBytes_throwsIOException_wrongPassword() = runTest {
            val keystoreBytes = File(KEYSTORE_P12_FILE).readBytes()
            assertFailsWith<IOException> {
                createProvider().readSmbCertificateFromBytes(keystoreBytes, KEYSTORE_ALIAS, "wrong")
            }
        }

        @Test
        fun signWithSmbKeyFromBytes_producesValidSignature_validKeystore() = runTest {
            val provider = createProvider()
            val keystoreBytes = File(KEYSTORE_P12_FILE).readBytes()
            val cert = provider.readSmbCertificateFromBytes(keystoreBytes, KEYSTORE_ALIAS, KEYSTORE_PASS)
            val data = "sign me".encodeToByteArray()
            val signature = provider.signWithSmbKeyFromBytes(data, keystoreBytes, KEYSTORE_ALIAS, KEYSTORE_PASS)
            assertTrue(signer.verify(extractPublicKey(cert), data, joseToDerEcdsa(signature)))
        }

        @Test
        fun signWithSmbKeyFromBytes_matchesSignWithSmbKey_forSameInput() = runTest {
            val provider = createProvider()
            val keystoreBytes = File(KEYSTORE_P12_FILE).readBytes()
            val cert = provider.readSmbCertificate(KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
            val data = "same data".encodeToByteArray()
            val sigFromFile = provider.signWithSmbKey(data, KEYSTORE_P12_FILE, KEYSTORE_ALIAS, KEYSTORE_PASS)
            val sigFromBytes = provider.signWithSmbKeyFromBytes(data, keystoreBytes, KEYSTORE_ALIAS, KEYSTORE_PASS)
            assertTrue(signer.verify(extractPublicKey(cert), data, joseToDerEcdsa(sigFromFile)))
            assertTrue(signer.verify(extractPublicKey(cert), data, joseToDerEcdsa(sigFromBytes)))
        }
    }
}
