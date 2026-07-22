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

package de.gematik.zeta.sdk.network.http.client.config.tls

public object ZetaSignatureAlgorithms {
    public val ALLOWED_LEAF: List<String> = listOf(
        "ecdsa_secp256r1_sha256",
        "ecdsa_secp384r1_sha384",
    )

    public val ALLOWED: List<String> = ALLOWED_LEAF + listOf(
        "ecdsa_secp521r1_sha512", // Stronger algorithm not specified
        "rsa_pkcs1_sha256", // Required for certificate chain validation
        "rsa_pkcs1_sha384", // Required for certificate chain validation
        "rsa_pkcs1_sha512", // Required for certificate chain validation
    )

    public val FORBIDDEN_HASH_FUNCTIONS: List<String> = listOf(
        "sha1", "md5", "sha224",
    )

    public const val MIN_EC_KEY_BITS: Int = 256

    private val TLS_SCHEME_TO_CERT_SIG_ALG: Map<String, String> = mapOf(
        "ecdsa_secp256r1_sha256" to "SHA256WITHECDSA",
        "ecdsa_secp384r1_sha384" to "SHA384WITHECDSA",
        "ecdsa_secp521r1_sha512" to "SHA512WITHECDSA",
        "rsa_pkcs1_sha256" to "SHA256WITHRSA",
        "rsa_pkcs1_sha384" to "SHA384WITHRSA",
        "rsa_pkcs1_sha512" to "SHA512WITHRSA",
    )

    public val ALLOWED_LEAF_CERT_SIG_ALGS: Set<String> = ALLOWED_LEAF.toCertSigAlgs()

    public val ALLOWED_CERT_SIG_ALGS: Set<String> = ALLOWED.toCertSigAlgs()

    private fun List<String>.toCertSigAlgs(): Set<String> = map { scheme ->
        TLS_SCHEME_TO_CERT_SIG_ALG[scheme]
            ?: error("No certificate signature algorithm mapping defined for TLS scheme '$scheme'")
    }.toSet()
}
