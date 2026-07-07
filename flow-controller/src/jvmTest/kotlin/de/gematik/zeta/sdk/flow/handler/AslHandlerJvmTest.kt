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

import de.gematik.zeta.sdk.asl.AslApi
import de.gematik.zeta.sdk.asl.AslException
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.handler.AslHandler
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.request.HttpRequestBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AslHandlerJvmTest {
    private val aslApi = mockk<AslApi>()
    private val flowCtx = mockk<FlowContext>()
    private val configStorage = mockk<ConfigurationStorage>()
    private val handler = AslHandler(aslApi)

    @BeforeTest
    fun setUp() {
        every { flowCtx.resourceScope } returns ResourceScope("https://resource.example.com", emptyList())
        every { flowCtx.configurationStorage } returns configStorage
        coEvery { aslApi.encrypt(any(), any()) } answers { firstArg() }
    }

    @Test
    fun canHandle_returnsTrue_forAsl() {
        assertTrue(handler.canHandle(FlowNeed.Asl))
    }

    @Test
    fun canHandle_returnsFalse_forAuthentication() {
        assertFalse(handler.canHandle(FlowNeed.Authentication))
    }

    @Test
    fun canHandle_returnsFalse_forClientRegistration() {
        assertFalse(handler.canHandle(FlowNeed.ClientRegistration))
    }

    @Test
    fun canHandle_returnsFalse_forAllNeedsExceptAsl() {
        listOf(FlowNeed.Asl, FlowNeed.Authentication, FlowNeed.ClientRegistration, FlowNeed.ConfigurationFiles)
            .filter { it != FlowNeed.Asl }
            .forEach { assertFalse(handler.canHandle(it), "Expected false for $it") }
    }

    @Test
    fun handle_returnsRetryRequest_whenAslUseIsRequired() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED

        assertIs<CapabilityResult.RetryRequest>(handler.handle(FlowNeed.Asl, flowCtx))
    }

    @Test
    fun handle_retryMutate_callsEncryptExactlyOnce_whenRequired() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED

        val result = handler.handle(FlowNeed.Asl, flowCtx) as CapabilityResult.RetryRequest
        result.mutate(HttpRequestBuilder())

        coVerify(exactly = 1) { aslApi.encrypt(any(), any()) }
    }

    @Test
    fun handle_returnsRetryRequest_whenAslUseIsRequiredPassthrough() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED_PASSTHROUGH

        assertIs<CapabilityResult.RetryRequest>(handler.handle(FlowNeed.Asl, flowCtx))
    }

    @Test
    fun handle_retryMutate_callsEncryptWithPassThroughTrue_whenRequiredPassthrough() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED_PASSTHROUGH

        val result = handler.handle(FlowNeed.Asl, flowCtx) as CapabilityResult.RetryRequest
        result.mutate(HttpRequestBuilder())

        coVerify { aslApi.encrypt(any(), true) }
    }

    @Test
    fun handle_retryMutate_callsEncryptExactlyOnce_whenRequiredPassthrough() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED_PASSTHROUGH

        val result = handler.handle(FlowNeed.Asl, flowCtx) as CapabilityResult.RetryRequest
        result.mutate(HttpRequestBuilder())
        coVerify(exactly = 1) { aslApi.encrypt(any(), any()) }
    }

    @Test
    fun handle_returnsDone_whenAslUseIsNotSupported() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.NOT_SUPPORTED

        assertIs<CapabilityResult.Done>(handler.handle(FlowNeed.Asl, flowCtx))
    }

    @Test
    fun handle_doesNotCallEncrypt_whenAslUseIsNotSupported() = runTest {
        coEvery { configStorage.aslUse() } returns ZetaAslUse.NOT_SUPPORTED

        handler.handle(FlowNeed.Asl, flowCtx)
        coVerify(exactly = 0) { aslApi.encrypt(any(), any()) }
    }

    @Test
    fun handle_returnsError_whenAslExceptionThrownDuringEncrypt() = runTest {
        val response = mockk<ZetaHttpResponse>(relaxed = true)
        val exception = AslException(response, "ASL handshake failed", 1)
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED
        coEvery { aslApi.encrypt(any(), any()) } throws exception

        val result = handler.handle(FlowNeed.Asl, flowCtx)
        assertIs<CapabilityResult.RetryRequest>(result)

        val mutateResult = runCatching { result.mutate(HttpRequestBuilder()) }
        assertTrue(mutateResult.isFailure)
        assertIs<AslException>(mutateResult.exceptionOrNull())
    }

    @Test
    fun handle_errorCode_isAslError_whenAslExceptionCaughtDirectly() = runTest {
        val response = mockk<ZetaHttpResponse>(relaxed = true)
        val exception = AslException(response, "timeout", 2)
        coEvery { configStorage.aslUse() } returns ZetaAslUse.REQUIRED
        coEvery { aslApi.encrypt(any(), any()) } throws exception

        val result = handler.handle(FlowNeed.Asl, flowCtx)

        assertIs<CapabilityResult.RetryRequest>(result)
    }
}
