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

package de.gematik.zeta.sdk

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.asl.AslApi
import de.gematik.zeta.sdk.asl.AslApiImpl
import de.gematik.zeta.sdk.asl.InnerHttpCodecImpl
import de.gematik.zeta.sdk.asl.aslDecryptionPlugin
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.configuration.ConfigurationApiImpl
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.FlowOrchestrator
import de.gematik.zeta.sdk.flow.ForwardingClient
import de.gematik.zeta.sdk.flow.handler.AslHandler
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.flow.handler.RetryHandler
import de.gematik.zeta.sdk.flow.zetaPlugin
import de.gematik.zeta.sdk.network.http.client.CompositeCookieStorage
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.SdkCookieStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import de.gematik.zeta.sdk.storage.provideSdkStorage
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.sdk.tpm.platformDefaultProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.util.appendAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock.System
import kotlin.time.measureTimedValue

/**
 * Zeta SDK entry point.
 *
 * Provides a DSL to create a configured [HttpClient] in a single call.
 *
 * Usage:
 * ```
 * val client = ZetaSdk.httpClient {
 *   timeouts(connectMs = 2_000, requestMs = 10_000)
 *   retry(setOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.NotFound), maxRetries = 3, onlyIdempotent = true)
 *   logLevel(LogLevel.INFO)
 * }
 * ```
 */

object ZetaSdk {
    fun build(
        resource: String,
        config: BuildConfig,
    ): ZetaSdkClient {
        val resourceScope = ResourceScope(resource, config.authConfig.scopes)
        val isolatedConfig = config.withNamespace(resourceScope.storageKey)
        return ZetaSdkClientImpl(resourceScope, isolatedConfig)
    }

    suspend fun ZetaSdkClient.forget(): Result<Unit> = runCatching {
        when (this) {
            is ZetaSdkClientImpl -> {
                flowContext.authenticationStorage.clear()
                flowContext.configurationStorage.clear()
                flowContext.clientRegistrationStorage.clear()
                flowContext.tpmStorage.clear()
                tpmProvider.forget()
                flowContext.aslStorage.clear()
                sdkCookieStorage.clearCookie()
                flowContext.revocationStorage.clear()
            }

            else -> this.logout().getOrThrow()
        }
    }

    suspend fun ZetaSdkClient.clearRegistration(): Result<Unit> = runCatching {
        when (this) {
            is ZetaSdkClientImpl -> {
                flowContext.authenticationStorage.clear()
                flowContext.configurationStorage.clear()
                flowContext.clientRegistrationStorage.clear()
                // the default TPM provider uses tpmStorage to store the DpopKeys
                // Thus no need to clear the TPM (which would clear the instance key as well)
                flowContext.tpmStorage.deleteAllDpopKeys()
                flowContext.aslStorage.clear()
                sdkCookieStorage.clearCookie()
            }

            else -> this.logout().getOrThrow()
        }
    }
}

class ZetaSdkClientImpl(
    val resourceScope: ResourceScope,
    private val cfg: BuildConfig,
) : ZetaSdkClient {
    private lateinit var mainHttpClient: ZetaHttpClient

    private val storage: SdkStorage = when (val storageConfig = cfg.storageConfig) {
        is StorageConfig.Default -> provideSdkStorage(storageConfig)
        is StorageConfig.Custom -> storageConfig.provider
    }

    val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
    private val compositeCookieStorage = CompositeCookieStorage(sdkCookieStorage)

    private val forwardingClient = ForwardingClient { builder ->
        mainHttpClient.request {
            takeFrom(builder)
        }
    }

    val flowContext = FlowContextImpl(resourceScope, forwardingClient, storage)

    private val revocationChecker = RevocationChecker(
        storage = flowContext.revocationStorage,
    )

    private val httpClientBuilder: ZetaHttpClientBuilder =
        (cfg.httpClientBuilder ?: ZetaHttpClientBuilder())
            .copy(
                baseUrl = resourceScope.fqdn,
                cookieStorage = compositeCookieStorage,
            )
            .revocationChecker(revocationChecker)

    init {
        cfg.logger?.let { Log.setLogger(it) }
    }

    private var clientRegistrationApiClient: ZetaHttpClient? = null
    private var authApiClient: ZetaHttpClient? = null
    private var aslApiClient: ZetaHttpClient? = null

    private val configHandler: ConfigurationHandler by lazy {
        ConfigurationHandler(ConfigurationApiImpl(httpClientBuilder))
    }
    val tpmProvider: TpmProvider = platformDefaultProvider(flowContext.tpmStorage)
    private val clientRegistrationHandler: ClientRegistrationHandler by lazy {
        ClientRegistrationHandler(cfg.clientName, ClientRegistrationApiImpl(httpClientBuilder.build().also { clientRegistrationApiClient = it }), tpmProvider)
    }

    private lateinit var accessTokenProvider: AccessTokenProvider
    private val authHandler: EnsureAccessTokenHandler by lazy {
        accessTokenProvider = AccessTokenProviderImpl(
            resourceScope.storageKey,
            cfg.authConfig,
            AuthenticationApiImpl(httpClientBuilder.build().also { authApiClient = it }),
            flowContext.authenticationStorage,
            { System.now().epochSeconds },
            tpmProvider,
        )
        EnsureAccessTokenHandler(
            accessTokenProvider,
            tpmProvider,
            authConfig = cfg.authConfig,
            cfg.productId,
            cfg.productVersion,
            cfg.platformProductId,
            clientRegistrationHandler,
        )
    }
    private lateinit var aslApi: AslApi
    private val aslHandler: AslHandler by lazy {
        aslApi = AslApiImpl(
            cfg.authConfig.aslProdEnvironment,
            cfg.authConfig.requiredRoleOid,
            flowContext.aslStorage,
            revocationChecker,
            httpClientBuilder.build().also { aslApiClient = it },
            accessTokenProvider,
            tpmProvider,
            !httpClientBuilder.isServerValidationDisabled,
        )

        AslHandler(aslApi)
    }

    private fun newOrchestrator(): FlowOrchestrator =
        FlowOrchestrator(
            handlers = listOf(
                configHandler,
                clientRegistrationHandler,
                authHandler,
                aslHandler,
                RetryHandler(),
            ),
        )

    override suspend fun discover(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            Log.i { "[SDK-DISCOVER] start" }
            configHandler.handle(FlowNeed.ConfigurationFiles, flowContext)
                .also { Log.i { "[SDK-DISCOVER] end" } }
        }
        Log.i { "[SDK-TIMING] discover (configHandler)=$time result=$result" }
        result.orThrow()
    }

    override suspend fun register(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            clientRegistrationHandler.handle(FlowNeed.ClientRegistration, flowContext)
        }
        Log.i { "[SDK-TIMING] register (clientRegistrationHandler)=$time result=$result" }
        result.orThrow()
    }

    override suspend fun authenticate(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            authHandler.handle(FlowNeed.Authentication, flowContext)
        }
        Log.i { "[SDK-TIMING] authenticate (authHandler)=$time result=$result" }
        result.orThrow()
    }

    /**
     * Fail the surrounding [runCatching] when a handler reports a failure, so the
     * returned [Result] reflects the outcome instead of silently succeeding.
     */
    private fun CapabilityResult.orThrow() {
        if (this is CapabilityResult.Error) {
            error("[$internalCode] $internalMessage")
        }
    }

    override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
        val orchestrator = newOrchestrator()
        mainHttpClient = httpClientBuilder
            .copy(cookieStorage = sdkCookieStorage)
            .apply(builder)
            .build(addExtras = {
                install(aslDecryptionPlugin(aslApi, InnerHttpCodecImpl()))
                install(zetaPlugin(orchestrator, flowContext))
            })

        return mainHttpClient
    }

    override suspend fun <R> ws(
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit,
        customHeaders: Map<String, String>?,
        block: suspend DefaultClientWebSocketSession.() -> R,
    ) = coroutineScope {
        discover().getOrThrow()
        register().getOrThrow()

        val (token, dpopKey) = authHandler.getValidAccessTokenWithStepUp(flowContext)
        require(token.isNotBlank())

        val hashedToken = accessTokenProvider.hash(token)
        val dpop = accessTokenProvider.createDpopToken(dpopKey.jwk, "GET", targetUrl, null, hashedToken)

        val wsClient = httpClientBuilder
            .copy(cookieStorage = sdkCookieStorage)
            .apply(builder)
            .build()

        wsClient.webSocket(request = {
            url(targetUrl)
            header(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} $token")
            header(HttpAuthHeaders.Dpop, dpop)
            header(HttpHeaders.Accept, "application/json")
            customHeaders?.let {
                headers.appendAll(customHeaders)
            }
        }) {
            block()
        }
    }

    override suspend fun status(): Result<SdkStatus> = runCatching {
        val authServer = flowContext.configurationStorage.getAuthServer()
            ?: return@runCatching SdkStatus.NOT_REGISTERED

        val regKey = authServer.registrationEndpoint?.takeIf { it.isNotBlank() } ?: authServer.issuer
        flowContext.clientRegistrationStorage.getRegistrationInfo(regKey)
            ?: return@runCatching SdkStatus.NOT_REGISTERED

        val nowEpoch = System.now().epochSeconds
        val expiresAt = flowContext.authenticationStorage.getTokenExpiration()?.toLongOrNull() ?: 0L
        val tokensExpired = expiresAt <= nowEpoch

        val accessToken = flowContext.authenticationStorage.getAccessToken()
        val refreshToken = flowContext.authenticationStorage.getRefreshToken()

        when {
            !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank() && !tokensExpired ->
                SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
            !refreshToken.isNullOrBlank() ->
                SdkStatus.HAS_REFRESH_TOKEN
            else ->
                SdkStatus.REGISTERED_NO_VALID_TOKENS
        }
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        flowContext.authenticationStorage.clear()
        flowContext.tpmStorage.deleteAllDpopKeys()
        sdkCookieStorage.clearCookie()
    }

    override suspend fun close(): Result<Unit> = runCatching {
        if (::mainHttpClient.isInitialized) mainHttpClient.close()
        clientRegistrationApiClient?.close()
        authApiClient?.close()
        aslApiClient?.close()
        sdkCookieStorage.clearCookie()
    }
}
