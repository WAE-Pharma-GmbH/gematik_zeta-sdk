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

import de.gematik.zeta.driver.model.SdkInstance
import de.gematik.zeta.driver.model.toKtorLogLevel
import de.gematik.zeta.logging.ZetaLogLevel
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LoadTestDriverTests {
    private lateinit var manager: FakeLoadInstanceManager

    @BeforeTest
    fun setup() {
        manager = FakeLoadInstanceManager()
    }

    @AfterTest
    fun teardown() {
        manager.clear()
    }

    @Test
    fun buildCreateResponse_returnsExpectedJson() = runTest {
        val result = buildCreateResponse(
            created = listOf(1, 2, 3),
            autoInit = true,
        )

        assertEquals(3, result["created"]!!.jsonPrimitive.int)
        assertEquals(true, result["autoInit"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(1, 2, 3), result["ids"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun buildCreateResponse_returnsEmptyIds_whenCreatedIsEmpty() = runTest {
        val result = buildCreateResponse(
            created = emptyList(),
            autoInit = false,
        )
        assertEquals(0, result["created"]!!.jsonPrimitive.int)
        assertEquals(false, result["autoInit"]!!.jsonPrimitive.boolean)
        assertEquals(emptyList(), result["ids"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun buildCreateResponse_preservesIdOrder_whenIdsAreProvided() = runTest {
        val ids = listOf(5, 3, 1, 4, 2)
        val result = buildCreateResponse(
            created = ids,
            autoInit = true,
        )
        assertEquals(ids, result["ids"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun buildCreateResponse_returnsFalseAutoInit_whenAutoInitIsFalse() = runTest {
        val result = buildCreateResponse(
            created = listOf(1),
            autoInit = false,
        )
        assertEquals(false, result["autoInit"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun resolveInstance_returnsBadIndex_whenIndexParamIsNull() = runTest {
        val result = resolveInstance(indexParam = null, manager = manager)
        assertIs<InstanceResolveResult.BadIndex>(result)
    }

    @Test
    fun resolveInstance_returnsBadIndex_whenIndexParamIsNotAnInteger() = runTest {
        val result = resolveInstance(indexParam = "notAnInt", manager = manager)
        assertIs<InstanceResolveResult.BadIndex>(result)
    }

    @Test
    fun resolveInstance_returnsNotFound_whenInstanceDoesNotExist() = runTest {
        val result = resolveInstance(indexParam = "99", manager = manager)
        assertIs<InstanceResolveResult.NotFound>(result)
        assertEquals(99, (result).index)
    }

    @Test
    fun handleCreateInstances_returnsEmptyIds_whenCountIsZero() = runTest {
        // Act
        val result = handleCreateInstances(count = 0, autoInit = false, configs = null, manager = manager)

        // Assert
        assertEquals(0, result["created"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), result["ids"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun handleListInstances_returnsEmptyArray_whenNoInstancesExist() = runTest {
        val result = handleListInstances(manager)
        assertEquals(0, result.size)
    }

    @Test
    fun handleDeleteInstances_returnsRemovedIds_whenIdsProvided() = runTest {
        // Arrange
        manager.deleteResult = listOf(1, 2)

        // Act
        val removed = handleDeleteInstances(ids = listOf(1, 2), manager = manager)

        // Assert
        assertEquals(listOf(1, 2), removed)
        assertEquals(listOf(1, 2), manager.deleteCalled)
    }

    @Test
    fun handleDeleteInstances_passesNull_whenNoIdsGiven() = runTest {
        // Act
        handleDeleteInstances(ids = null, manager = manager)

        // Assert
        assertEquals(null, manager.deleteCalled)
    }

    @Test
    fun handleDeleteInstances_returnsEmptyList_whenNoInstancesMatch() = runTest {
        // Act
        val removed = handleDeleteInstances(ids = listOf(999), manager = manager)

        // Assert
        assertEquals(emptyList(), removed)
    }

    @Test
    fun toKtorLogLevel_debug_mapsToAll() {
        assertEquals(LogLevel.ALL, ZetaLogLevel.DEBUG.toKtorLogLevel())
    }

    @Test
    fun toKtorLogLevel_info_mapsToInfo() {
        assertEquals(LogLevel.INFO, ZetaLogLevel.INFO.toKtorLogLevel())
    }

    @Test
    fun toKtorLogLevel_warn_mapsToInfo() {
        assertEquals(LogLevel.INFO, ZetaLogLevel.WARN.toKtorLogLevel())
    }

    @Test
    fun toKtorLogLevel_error_mapsToNone() {
        assertEquals(LogLevel.NONE, ZetaLogLevel.ERROR.toKtorLogLevel())
    }

    @Test
    fun toKtorLogLevel_none_mapsToNone() {
        assertEquals(LogLevel.NONE, ZetaLogLevel.NONE.toKtorLogLevel())
    }

    @Test
    fun toKtorLogLevel_allValuesAreMapped() {
        ZetaLogLevel.entries.forEach { level ->
            assertNotNull(level.toKtorLogLevel())
        }
    }
}

class FakeLoadInstanceManager : LoadInstanceManager() {
    var instances: Map<Int, SdkInstance> = emptyMap()
    var deleteResult: List<Int> = emptyList()
    var listResult: List<SdkInstance> = emptyList()
    val initCalled = mutableListOf<Int>()
    var deleteCalled: List<Int>? = null

    override fun getInstance(id: Int) = instances[id]
    override fun listInstances() = listResult
    override suspend fun deleteInstances(ids: List<Int>?): List<Int> { deleteCalled = ids; return deleteResult }
    override suspend fun initializeInstance(id: Int): Boolean { initCalled += id; return true }
}
