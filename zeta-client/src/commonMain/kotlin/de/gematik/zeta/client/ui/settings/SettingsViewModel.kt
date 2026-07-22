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

package de.gematik.zeta.client.ui.settings

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import com.ensody.reactivestate.ReactiveViewModel
import de.gematik.zeta.client.data.repository.SettingsRepository
import de.gematik.zeta.client.ui.common.mvi.MviState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface SettingsState : MviState {
    public data class Result(
        val tlsValidationEnabled: Boolean,
        val pemFilePath: String? = null,
    ) : SettingsState
}

@OptIn(ExperimentalReactiveStateApi::class)
public class SettingsViewModel(
    scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
) : ReactiveViewModel(scope) {
    private val _state = MutableStateFlow<SettingsState>(
        SettingsState.Result(tlsValidationEnabled = true),
    )
    public val state: StateFlow<SettingsState> = _state.asStateFlow()

    public fun loadSettings() {
        scope.launch {
            val enabled = settingsRepository.getTlsValidationEnabled()
            val pemPath = settingsRepository.getPemFilePath()
            _state.value = SettingsState.Result(enabled, pemPath)
        }
    }

    public fun setTlsValidationEnabled(enabled: Boolean) {
        val current = _state.value as? SettingsState.Result ?: return
        _state.value = current.copy(tlsValidationEnabled = enabled)
        scope.launch {
            settingsRepository.setTlsValidationEnabled(enabled)
        }
    }

    public fun setPemFile(path: String?) {
        val current = _state.value as? SettingsState.Result ?: return
        _state.value = current.copy(pemFilePath = path)
        scope.launch {
            settingsRepository.setPemFilePath(path)
        }
    }
}
