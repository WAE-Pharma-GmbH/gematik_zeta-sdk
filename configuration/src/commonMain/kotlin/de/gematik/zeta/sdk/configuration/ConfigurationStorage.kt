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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.PRESENT_MARKER
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

interface ConfigurationStorage {
    suspend fun getProtectedResource(): ProtectedResourceMetadata?
    suspend fun saveProtectedResource(protectedRes: String): ProtectedResourceMetadata
    suspend fun getAuthServers(): List<AuthorizationServerMetadata>
    suspend fun getAuthServer(): AuthorizationServerMetadata?
    suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata)
    suspend fun aslUse(): ZetaAslUse
    suspend fun clear()
}

class ConfigurationStorageImpl(
    sdkStorage: SdkStorage,
    resourceScope: ResourceScope,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationStorage {
    private val storage = ExtendedStorage(sdkStorage, resourceScope)

    companion object {
        const val PR_PREFIX = "pr:"
        const val AS_PREFIX = "as:"
        const val PR_INDEX = "pr_index"
        const val AS_INDEX = "as_index"
        const val RS_TO_AS = "rs_to_as"
    }

    private fun prKey() = "${PR_PREFIX}resource"
    private fun asKey(authFqdn: String) = "$AS_PREFIX$authFqdn"

    override suspend fun getProtectedResource(): ProtectedResourceMetadata? {
        Log.i { "[ZETA-SDK] getProtectedResource" }
        val raw = storage.get(prKey()) ?: return null
        return runCatching { json.decodeFromString<ProtectedResourceMetadata>(raw) }.getOrNull()
    }

    override suspend fun saveProtectedResource(protectedRes: String): ProtectedResourceMetadata {
        val parsed = runCatching { json.decodeFromString<ProtectedResourceMetadata>(protectedRes) }
            .getOrElse { e ->
                Log.e { "Failed to parse ProtectedResourceMetadata: ${e.message}" }
                throw e
            }
        storage.put(prKey(), protectedRes)
        storage.upsertStringMap(PR_INDEX) { it["resource"] = PRESENT_MARKER }
        return parsed
    }

    override suspend fun getAuthServers(): List<AuthorizationServerMetadata> {
        val index = storage.getMap(AS_INDEX) ?: return emptyList()
        return index.keys.mapNotNull { authFqdn ->
            val raw = storage.get(asKey(authFqdn)) ?: return@mapNotNull null
            runCatching { json.decodeFromString<AuthorizationServerMetadata>(raw) }.getOrNull()
        }
    }

    override suspend fun getAuthServer(): AuthorizationServerMetadata? {
        val authFqdn = storage.getMap(RS_TO_AS)?.get("resource") ?: return null
        val raw = storage.get(asKey(authFqdn)) ?: return null
        return runCatching { json.decodeFromString<AuthorizationServerMetadata>(raw) }.getOrNull()
    }

    override suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata) {
        val authFqdn = hostOf(authServerMetadata.issuer)
        val desired = json.encodeToString(authServerMetadata)
        if (storage.get(asKey(authFqdn)) != desired) {
            storage.put(asKey(authFqdn), desired)
        }
        storage.upsertStringMap(RS_TO_AS) { it["resource"] = authFqdn }
        storage.upsertStringMap(AS_INDEX) { it[authFqdn] = PRESENT_MARKER }
    }

    override suspend fun aslUse(): ZetaAslUse =
        getProtectedResource()?.zetaAslUse ?: error("OPR not found")

    override suspend fun clear() {
        Log.d { "Clearing all configuration caches" }
        storage.remove(prKey())
        storage.remove(PR_INDEX)
        storage.getMap(AS_INDEX)?.keys?.forEach { storage.remove(asKey(it)) }
        storage.remove(AS_INDEX)
        storage.remove(RS_TO_AS)
    }
}
