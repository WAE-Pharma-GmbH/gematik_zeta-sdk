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

package de.gematik.zeta.client.data.service.http

import de.gematik.zeta.client.data.service.smb.HardcodedTokenProvider
import de.gematik.zeta.client.di.DIContainer.ASL_PROD
import de.gematik.zeta.client.di.DIContainer.CUSTOM_SMCB_ENABLED
import de.gematik.zeta.client.di.DIContainer.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.client.di.DIContainer.REQUIRED_OID
import de.gematik.zeta.client.di.DIContainer.SMB_KEYSTORE_CREDENTIALS
import de.gematik.zeta.client.di.DIContainer.STORAGE_AES_KEY
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import kotlin.io.encoding.Base64

public interface HttpClientProvider {
    public fun provideHttpClient(): ZetaHttpClient
    public fun setupEnvUrl(url: String)
    public suspend fun forget()
    public suspend fun clearRegistration()
    public suspend fun logout()
    public suspend fun status(): SdkStatus
    public fun updateTlsValidation(enabled: Boolean)
    public fun updateTrustedCa(pemPath: String?)
    public suspend fun discover()
    public suspend fun register()
    public suspend fun authenticate()
}

private const val demoClient = "ZETA-Test-Client"

public class HttpClientProviderImpl : HttpClientProvider {
    private lateinit var httpClient: ZetaHttpClient
    private lateinit var sdkClient: ZetaSdkClient
    private lateinit var currentUrl: String

    private var tlsValidationEnabled: Boolean = !DISABLE_SERVER_VALIDATION
    private var trustedCaPemPath: String? = null

    override fun provideHttpClient(): ZetaHttpClient =
        httpClient

    override fun setupEnvUrl(url: String) {
        currentUrl = url
        httpClient = prepareHttpClient(url)
    }

    override fun updateTlsValidation(enabled: Boolean) {
        tlsValidationEnabled = enabled
        rebuildIfReady()
    }

    override fun updateTrustedCa(pemPath: String?) {
        trustedCaPemPath = pemPath
        rebuildIfReady()
    }

    private fun rebuildIfReady() {
        if (::currentUrl.isInitialized) {
            httpClient = prepareHttpClient(currentUrl)
        }
    }

    override suspend fun authenticate() {
        sdkClient.authenticate()
    }

    override suspend fun register() {
        sdkClient.register()
    }

    override suspend fun discover() {
        sdkClient.discover()
    }

    override suspend fun forget() {
        sdkClient.forget()
    }

    override suspend fun clearRegistration() {
        sdkClient.clearRegistration()
    }

    override suspend fun logout() {
        sdkClient.logout()
    }

    override suspend fun status(): SdkStatus = sdkClient.status().getOrThrow()

    private fun prepareHttpClient(url: String): ZetaHttpClient {
        sdkClient = ZetaSdk.build(
            resource = url,
            config = BuildConfig(
                demoClient,
                productVersion = "1.0.0",
                "demo-client",
                StorageConfig.Default(STORAGE_AES_KEY),
                object : TpmConfig {},
                AuthConfig(
                    listOf("zero:audience"),
                    30,
                    ASL_PROD,
                    when {
                        SMB_KEYSTORE_CREDENTIALS.keystoreFile.isNotEmpty() ->
                            SmbTokenProvider(SMB_KEYSTORE_CREDENTIALS)

                        CUSTOM_SMCB_ENABLED ->
                            CustomSmcbTokenProvider(
                                connectorApi = object : CustomConnectorApi {
                                    override suspend fun readCertificate(): ByteArray {
                                        // Implement your own certificate retrieval here.
                                        // Return the raw DER-encoded X.509 certificate bytes.
                                        // Example: call your own connector service, HSM, or card reader.
                                        // return myConnector.getCertificate()

                                        return Base64.decode("/ASGePjrYWaieIxzCi1+wEBqjVPQ83x7DOZDuA=...")
                                    }

                                    override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
                                        // Implement your own signing here.
                                        // base64Challenge: base64-encoded hash of the token to sign.
                                        // Return the raw DER-encoded ECDSA signature bytes.
                                        // Example: call your HSM or card reader to sign the challenge.
                                        // return myConnector.sign(base64Challenge)

                                        return Base64.decode("/ASGePjrYWaieIxzCi1+wEBqjVPQ83x7DOZDuA=...")
                                    }
                                },
                            )
                        else -> HardcodedTokenProvider()
                    },
                    AttestationConfig.software(),
                    requiredRoleOid = REQUIRED_OID ?: "",
                ),
                getPlatformProduct(),
                ZetaHttpClientBuilder()
                    .disableServerValidation(!tlsValidationEnabled)
                    .let { builder ->
                        trustedCaPemPath?.let { path ->
                            builder.addCaPemFile(path)
                        } ?: builder
                    }
                    .logging(LogLevel.ALL),
            ),
        )

        return sdkClient.httpClient {
            logging(LogLevel.ALL)
            disableServerValidation(!tlsValidationEnabled)
            contentNegotiation(true)
        }
    }

    private fun getPlatformProduct(): PlatformProductId {
        return when (val plat = platform()) {
            is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
            is Platform.Jvm.Linux -> PlatformProductId.LinuxProductId("linux", "", demoClient, "0.5.0")
            is Platform.Jvm.Windows -> PlatformProductId.WindowsProductId("windows", "", demoClient)
            else -> error("Unknown platform: $plat")
        }
    }
}
