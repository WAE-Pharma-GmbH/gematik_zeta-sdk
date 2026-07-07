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

package de.gematik.zeta.sdk.clientregistration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

interface ClientRegistrationStorage {
    suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse)
    suspend fun getRegistrationInfo(authServer: String): ClientRegistrationResponse?
    suspend fun getClientId(authServer: String): String?
    suspend fun clear()
}

class ClientRegistrationStorageImpl(
    sdkStorage: SdkStorage,
    resourceScope: ResourceScope,
) : ClientRegistrationStorage {
    private val storage = ExtendedStorage(sdkStorage, resourceScope)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val MAP_KEY = "client_registration_by_auth_server"
    }

    override suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse) {
        Log.d { "Saving client registration for AS: $authServer" }
        storage.upsertStringMap(MAP_KEY) { it[authServer] = json.encodeToString(registrationResponse) }
    }

    override suspend fun getClientId(authServer: String): String? =
        getRegistrationInfo(authServer)?.clientId

    override suspend fun getRegistrationInfo(authServer: String): ClientRegistrationResponse? {
        if (authServer.isBlank()) return null
        Log.d { "Getting client registration for AS: $authServer" }
        val raw = storage.getMap(MAP_KEY)?.get(authServer) ?: return null
        return runCatching { json.decodeFromString<ClientRegistrationResponse>(raw) }
            .onFailure { Log.e { "Failed to decode registration for $authServer: ${it.message}" } }
            .getOrNull()
    }

    override suspend fun clear() {
        Log.d { "Removing client registration" }
        storage.remove(MAP_KEY)
    }
}
