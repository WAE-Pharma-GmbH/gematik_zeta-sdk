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

import de.gematik.zeta.logging.Log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import platform.windows.CERT_CONTEXT
import platform.windows.CertCloseStore
import platform.windows.CertEnumCertificatesInStore
import platform.windows.CertOpenSystemStoreW
import platform.windows.GetLastError
import kotlin.io.encoding.Base64

private var cachedBundle: ByteArray? = null
private var cachedAt: Long = 0
private const val CACHE_TTL_MS = 2 * 60 * 1000L // 2 minutes

internal actual fun platformDefaultCaBundlePem(): ByteArray? {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    if (cachedBundle != null && (now - cachedAt) < CACHE_TTL_MS) {
        return cachedBundle
    }
    Log.d { "[ZETA-TLS] platformDefaultCaBundlePem: reading Windows CA bundle (cache expired or empty)" }
    val bundle = buildWindowsRootCaBundlePem()?.encodeToByteArray()
    cachedBundle = bundle
    cachedAt = now
    return bundle
}

@OptIn(ExperimentalForeignApi::class)
private fun buildWindowsRootCaBundlePem(): String? {
    Log.d { "[ZETA-TLS] buildWindowsRootCaBundlePem: opening ROOT store" }

    val hStore = CertOpenSystemStoreW(0uL, "ROOT")
    if (hStore == null) {
        Log.w { "[ZETA-TLS] buildWindowsRootCaBundlePem: CertOpenSystemStore(ROOT) failed, GetLastError=${GetLastError()}, falling back to curl default trust store" }
        return null
    }

    val sb = StringBuilder()
    var certCount = 0

    try {
        var pCertContext: CPointer<CERT_CONTEXT>? = null
        while (true) {
            pCertContext = CertEnumCertificatesInStore(hStore, pCertContext) ?: break
            val ctx = pCertContext.pointed
            runCatching {
                val derBytes = ctx.pbCertEncoded!!.readBytes(ctx.cbCertEncoded.toInt())
                sb.append(derToPem(derBytes))
                certCount++
            }.onFailure {
                Log.w { "[ZETA-TLS] buildWindowsRootCaBundlePem: skipping unreadable cert #${certCount + 1}: ${it.message}" }
            }
        }
    } catch (e: Exception) {
        Log.w { "[ZETA-TLS] buildWindowsRootCaBundlePem: enumeration failed after $certCount certs: ${e.message}, falling back to curl default trust store" }
        return null
    } finally {
        CertCloseStore(hStore, 0u)
    }

    if (certCount == 0) {
        Log.w { "[ZETA-TLS] buildWindowsRootCaBundlePem: ROOT store returned zero certificates, falling back to curl default trust store" }
        return null
    }

    Log.d { "[ZETA-TLS] buildWindowsRootCaBundlePem: done, $certCount certs, ${sb.length} PEM chars total" }
    return sb.toString()
}

private fun derToPem(der: ByteArray): String {
    val wrapped = Base64.encode(der).chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----\n"
}
