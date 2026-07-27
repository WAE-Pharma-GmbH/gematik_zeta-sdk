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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationApiImplTest {
    @Test
    fun fetchResourceMetadata_returnsBody_whenOk() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val ktorClient = HttpClient(engine)
        val zetaClient = ZetaHttpClient(ktorClient)

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(newUrl: String): ZetaHttpClient {
                assertEquals("https://example.com/.well-known/", newUrl)
                return zetaClient
            }
        }

        val api = ConfigurationApiImpl(builder)

        val result = api.fetchResourceMetadata("https://example.com")

        assertEquals("""{"ok":true}""", result)
    }

    @Test
    fun fetchResourceMetadata_throws_whenNotOk() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"bad"}""",
                status = HttpStatusCode.BadRequest,
            )
        }

        val ktorClient = HttpClient(engine)
        val zetaClient = ZetaHttpClient(ktorClient)

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(newUrl: String): ZetaHttpClient = zetaClient
        }

        val api = ConfigurationApiImpl(builder)

        val error = assertFailsWith<ServiceDiscoveryException> {
            api.fetchResourceMetadata("https://example.com")
        }

        assertEquals(
            "Service discovery failed to load resource: https://example.com/.well-known/oauth-protected-resource",
            error.message,
        )
    }

    @Test
    fun fetchAuthorizationMetadata_callsCorrectBaseUrl() = runTest {
        var baseUrlCaptured: String? = null

        val engine = MockEngine {
            respond(
                content = """{"auth":true}""",
                status = HttpStatusCode.OK,
            )
        }

        val zetaClient = ZetaHttpClient(HttpClient(engine))

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(newUrl: String): ZetaHttpClient {
                baseUrlCaptured = newUrl
                return zetaClient
            }
        }

        val api = ConfigurationApiImpl(builder)

        val result = api.fetchAuthorizationMetadata("https://auth.example.com")

        assertEquals("""{"auth":true}""", result)
        assertEquals("https://auth.example.com/.well-known/", baseUrlCaptured)
    }

    @Test
    fun fetchResourceMetadata_keepsCustomPort() = runTest {
        var baseUrlCaptured: String? = null

        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK)
        }

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(newUrl: String): ZetaHttpClient {
                baseUrlCaptured = newUrl
                return ZetaHttpClient(HttpClient(engine))
            }
        }

        val api = ConfigurationApiImpl(builder)

        api.fetchResourceMetadata("https://example.com:8443/test")

        assertEquals("https://example.com:8443/.well-known/", baseUrlCaptured)
    }
}
