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

package de.gematik.zeta.sdk.authentication.smcb

/**
 * Custom connector for SMC-B card operations.
 *
 * Example flow during authentication:
 * ```
 * 1. readCertificate()
 * val certResponse = konnektor.readCardCertificate(cardHandle, mandantId, ...)
 * return Base64.decode(certResponse.x509Certificate) // raw DER bytes
 *
 * 2. externalAuthenticate("dBVkMwlvdFOW2e0b4JEu2A...")
 * val sigResponse = konnektor.externalAuthenticate(cardHandle, base64Challenge)
 * return Base64.decode(sigResponse.base64Signature) // raw DER-encoded ECDSA
 *
 * Note: the SDK converts DER to compact JOSE (r || s) internally
 */
interface CustomConnectorApi {

    /** Returns the SMC-B X.509 certificate as raw DER bytes (not Base64, not PEM). */
    suspend fun readCertificate(): ByteArray

    /**
     * Signs the given Base64url-encoded challenge using the SMC-B private key (ES256).
     * Must return a raw DER-encoded ECDSA signature. Do not convert to JOSE format.
     */
    suspend fun externalAuthenticate(base64Challenge: String): ByteArray
}

class CustomSmcbTokenProvider(
    private val connectorApi: CustomConnectorApi,
) : BaseSmcbTokenProvider() {
    override suspend fun readCertificate() = connectorApi.readCertificate()
    override suspend fun externalAuthenticate(base64Challenge: String) =
        connectorApi.externalAuthenticate(base64Challenge)
}
