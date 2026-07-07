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

package de.gematik.zeta.logging

public object Log {
    public var logLevel: ZetaLogLevel = ZetaLogLevel.ERROR
        private set
    private var customLogger: ZetaLogger? = null

    public fun clearDestinations() {
        logLevel = ZetaLogLevel.ERROR
        customLogger = null
    }
    public fun setLogLevel(level: ZetaLogLevel) { logLevel = level }
    public fun setLogger(logger: ZetaLogger) { customLogger = logger }
    public fun initDebugLogger() { logLevel = ZetaLogLevel.DEBUG }

    private fun isEnabled(level: ZetaLogLevel): Boolean =
        logLevel != ZetaLogLevel.NONE && level.ordinal >= logLevel.ordinal

    public fun d(throwable: Throwable? = null, tag: String? = null, message: () -> String) {
        if (!isEnabled(ZetaLogLevel.DEBUG)) return
        try {
            customLogger?.d(tag, message, throwable)
                ?: println("[DEBUG] [${tag ?: "Zeta"}] ${message()}")
        } catch (e: Exception) {
            println("[LOG-ERROR] d failed: ${e::class.simpleName} - ${e.message}")
        }
    }

    public fun i(throwable: Throwable? = null, tag: String? = null, message: () -> String) {
        if (!isEnabled(ZetaLogLevel.INFO)) return
        try {
            customLogger?.i(tag, message, throwable)
                ?: println("[INFO] [${tag ?: "Zeta"}] ${message()}")
        } catch (e: Exception) {
            println("[LOG-ERROR] i failed: ${e::class.simpleName} - ${e.message}")
        }
    }

    public fun w(throwable: Throwable? = null, tag: String? = null, message: () -> String) {
        if (!isEnabled(ZetaLogLevel.WARN)) return
        try {
            customLogger?.w(tag, message, throwable)
                ?: println("[WARN] [${tag ?: "Zeta"}] ${message()}")
        } catch (e: Exception) {
            println("[LOG-ERROR] w failed: ${e::class.simpleName} - ${e.message}")
        }
    }

    public fun e(throwable: Throwable? = null, tag: String? = null, message: () -> String) {
        if (!isEnabled(ZetaLogLevel.ERROR)) return
        try {
            customLogger?.e(tag, message, throwable)
                ?: println("[ERROR] [${tag ?: "Zeta"}] ${message()}")
        } catch (e: Exception) {
            println("[LOG-ERROR] e failed: ${e::class.simpleName} - ${e.message}")
        }
    }
}

public enum class ZetaLogLevel {
    DEBUG, INFO, WARN, ERROR, NONE
}
