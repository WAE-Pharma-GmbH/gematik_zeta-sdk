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
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

public class CompositeCookieStorage(
    private val sdkStorage: SdkCookieStorage,
    private val defaultStorage: AcceptAllCookiesStorage = AcceptAllCookiesStorage(),
) : CookiesStorage by defaultStorage {

    public companion object {
        public const val ZETA_ROUTE_COOKIE: String = "zeta_route"
    }
    override suspend fun get(requestUrl: Url): List<Cookie> =
        sdkStorage.get(requestUrl) + defaultStorage.get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (cookie.name == ZETA_ROUTE_COOKIE) {
            sdkStorage.addCookie(requestUrl, cookie)
        } else {
            defaultStorage.addCookie(requestUrl, cookie)
        }
    }
}

public class SdkCookieStorage(
    storage: SdkStorage,
    resourceScope: ResourceScope,
) : CookiesStorage {
    private val mutex = Mutex()
    private val extendedStorage = ExtendedStorage(storage, resourceScope)
    private val storageKey = "cookies:${CompositeCookieStorage.ZETA_ROUTE_COOKIE}:${resourceScope.storageKey}"

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val value = extendedStorage.get(storageKey)
        Log.d { "[SDK-COOKIE]: key=$storageKey value=$value" }
        value?.let { listOf(Cookie(name = CompositeCookieStorage.ZETA_ROUTE_COOKIE, value = it)) } ?: emptyList()
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie): Unit = mutex.withLock {
        Log.d { "[SDK-COOKIE]: storing cookie key=$storageKey value=${cookie.value}" }
        extendedStorage.put(storageKey, cookie.value)
    }

    public suspend fun clearCookie(): Unit = mutex.withLock {
        Log.d { "[SDK-COOKIE]: clearing cookie key=$storageKey" }
        println("[SDK-COOKIE]: clearing cookie key=$storageKey hash=${extendedStorage.hash(storageKey)}")
        extendedStorage.remove(storageKey)
    }

    override fun close() { /* no-op */ }
}

@Serializable
public data class SerializableCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val maxAge: Int? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
) {
    public fun toCookie(): Cookie = Cookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        maxAge = maxAge,
        secure = secure,
        httpOnly = httpOnly,
    )

    public companion object {
        public fun from(c: Cookie): SerializableCookie = SerializableCookie(
            name = c.name,
            value = c.value,
            domain = c.domain,
            path = c.path,
            maxAge = c.maxAge,
            secure = c.secure,
            httpOnly = c.httpOnly,
        )
    }
}
