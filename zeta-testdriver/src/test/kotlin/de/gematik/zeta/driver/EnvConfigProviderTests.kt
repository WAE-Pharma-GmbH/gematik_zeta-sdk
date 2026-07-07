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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.model.SdkInstanceConfig
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvConfigProviderTest {

    @Test
    fun buildConfigFromProperties_returnsConfig_whenAllPropertiesProvided() {
        // Arrange
        val props = Properties().apply {
            setProperty("FACHDIENST_URL_1", "https://test1.example.com")
            setProperty("SMB_KEYSTORE_ALIAS_1", "alias1")
            setProperty("POPP_TOKEN_1", "token1")
        }
        val provider = EnvConfigProvider()

        // Act
        val result = provider.buildConfigFromProperties(props, 1)

        // Assert
        assertEquals("https://test1.example.com", result.fachdienstUrl)
        assertEquals("alias1", result.smbKeystoreAlias)
        assertEquals("token1", result.poppToken)
    }

    @Test
    fun buildConfigFromProperties_usesInstanceSpecificValue_whenAvailable() {
        // Arrange
        val props = Properties().apply {
            setProperty("FACHDIENST_URL_1", "https://test1.example.com")
            setProperty("FACHDIENST_URL_2", "https://test2.example.com")
        }
        val provider = EnvConfigProvider()

        // Act
        val result = provider.buildConfigFromProperties(props, 2)

        // Assert
        assertEquals("https://test2.example.com", result.fachdienstUrl)
    }

    @Test
    fun buildConfigFromProperties_fallsBackToDefault_whenInstanceSpecificNotAvailable() {
        // Arrange
        val props = Properties().apply {
            setProperty("FACHDIENST_URL_1", "https://default.example.com")
            setProperty("SMB_KEYSTORE_ALIAS_1", "default-alias")
        }
        val provider = EnvConfigProvider()

        // Act
        val result = provider.buildConfigFromProperties(props, 5)

        // Assert
        assertEquals("https://default.example.com", result.fachdienstUrl)
        assertEquals("default-alias", result.smbKeystoreAlias)
    }

    @Test
    fun buildConfigFromProperties_throwsError_whenFachdienstUrlMissing() {
        // Arrange
        val props = Properties()
        val provider = EnvConfigProvider()

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            provider.buildConfigFromProperties(props, 1)
        }
    }

    @Test
    fun mergeWithEnvFallback_prefersProvidedKeystoreFile_whenNotBlank() {
        // Arrange
        val provider = EnvConfigProvider()
        val provided = SdkInstanceConfig(
            fachdienstUrl = "https://test.com",
            smbKeystoreFile = "provided.p12",
            smbKeystoreB64 = "base64data",
            smbKeystoreAlias = "",
            smbKeystorePassword = "",
            aslProdEnv = true,
            poppToken = "",
            requiredOid = "",
        )

        // Act
        val result = provider.mergeWithEnvFallback(provided)

        // Assert
        assertEquals("provided.p12", result.smbKeystoreFile)
        assertEquals("", result.smbKeystoreB64)
    }

    @Test
    fun mergeWithEnvFallback_usesKeystoreB64_whenFileIsBlank() {
        // Arrange
        val provider = EnvConfigProvider()
        val provided = SdkInstanceConfig(
            fachdienstUrl = "https://test.com",
            smbKeystoreFile = "",
            smbKeystoreB64 = "base64data",
            smbKeystoreAlias = "",
            smbKeystorePassword = "",
            aslProdEnv = true,
            poppToken = "",
            requiredOid = "",
        )

        // Act
        val result = provider.mergeWithEnvFallback(provided)

        // Assert
        assertEquals("", result.smbKeystoreFile)
        assertEquals("base64data", result.smbKeystoreB64)
    }
}
