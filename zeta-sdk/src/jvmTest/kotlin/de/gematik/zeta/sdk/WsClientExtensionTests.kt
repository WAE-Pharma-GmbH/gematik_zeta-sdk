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

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WsClientExtensionTest {
    @Test
    fun stompConnectFrame_generatesCorrectFormat() {
        // Act
        val frame = stompConnectFrame("localhost")

        // Assert
        assertTrue(frame.startsWith("CONNECT\n"))
        assertTrue(frame.contains("accept-version:1.2\n"))
        assertTrue(frame.contains("host:localhost\n"))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompSubscribeFrame_generatesCorrectFormat() {
        // Act
        val frame = stompSubscribeFrame("sub-1", "/topic/messages")

        // Assert
        assertTrue(frame.startsWith("SUBSCRIBE\n"))
        assertTrue(frame.contains("id:sub-1\n"))
        assertTrue(frame.contains("destination:/topic/messages\n"))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompSendFrame_generatesCorrectFormat() {
        // Arrange
        val json = """{"message":"Hello"}"""

        // Act
        val frame = stompSendFrame("/app/chat", json)

        // Assert
        assertTrue(frame.startsWith("SEND\n"))
        assertTrue(frame.contains("destination:/app/chat\n"))
        assertTrue(frame.contains("content-type:application/json\n"))
        assertTrue(frame.contains(json))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompConnectFrame_withEmptyHost_generatesValidFrame() {
        // Act
        val frame = stompConnectFrame("")

        // Assert
        assertTrue(frame.contains("host:\n"))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompSubscribeFrame_withSpecialCharacters_generatesValidFrame() {
        // Act
        val frame = stompSubscribeFrame("sub-123", "/topic/special!@#$")

        // Assert
        assertTrue(frame.contains("id:sub-123\n"))
        assertTrue(frame.contains("destination:/topic/special!@#$\n"))
    }

    @Test
    fun stompSendFrame_withEmptyJson_generatesValidFrame() {
        // Act
        val frame = stompSendFrame("/app/test", "")

        // Assert
        assertTrue(frame.startsWith("SEND\n"))
        assertTrue(frame.contains("destination:/app/test\n"))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompSendFrame_withComplexJson_generatesValidFrame() {
        // Arrange
        val complexJson = """{"user":"john","data":{"count":42,"items":["a","b","c"]}}"""

        // Act
        val frame = stompSendFrame("/app/data", complexJson)

        // Assert
        assertTrue(frame.contains(complexJson))
        assertTrue(frame.endsWith("\u0000"))
    }

    @Test
    fun stompFrames_allEndWithNullCharacter() {
        // Act & Assert
        assertEquals(stompConnectFrame("host").last(), '\u0000')
        assertEquals(stompSubscribeFrame("id", "dest").last(), '\u0000')
        assertEquals(stompSendFrame("dest", "{}").last(), '\u0000')
    }

    @Test
    fun wsMessage_Text_equality() {
        // Arrange
        val msg1 = WsClientExtension.WsMessage.Text("test")
        val msg2 = WsClientExtension.WsMessage.Text("test")
        val msg3 = WsClientExtension.WsMessage.Text("different")

        // Assert
        assertEquals(msg1, msg2)
        assertNotEquals(msg1, msg3)
    }

    @Test
    fun wsMessage_Binary_equality() {
        // Arrange
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(1, 2, 3)
        val msg1 = WsClientExtension.WsMessage.Binary(data1)
        val msg2 = WsClientExtension.WsMessage.Binary(data2)

        // Assert
        assertContentEquals(msg1.bytes, msg2.bytes)
    }

    @Test
    fun wsMessage_Close_isSingleton() {
        // Arrange & Act
        val close1 = WsClientExtension.WsMessage.Close
        val close2 = WsClientExtension.WsMessage.Close

        // Assert
        assertSame(close1, close2)
    }

    @Test
    fun wsSession_sendText_sendsTextFrame() = runTest {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        coEvery { mockSession.send(any<Frame.Text>()) } just Runs

        val session = WsClientExtension.WsSession(mockSession)
        session.sendText("hello")

        coVerify { mockSession.send(match<Frame.Text> { it.readText() == "hello" }) }
    }

    @Test
    fun wsSession_sendBinary_sendsBinaryFrame() = runTest {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        coEvery { mockSession.send(any<Frame.Binary>()) } just Runs

        val session = WsClientExtension.WsSession(mockSession)
        session.sendBinary(byteArrayOf(1, 2, 3))

        coVerify { mockSession.send(match<Frame.Binary> { it.readBytes().contentEquals(byteArrayOf(1, 2, 3)) }) }
    }

    @Test
    fun wsSession_receiveNext_returnsNullWhenChannelClosed() = runTest {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>()
        every { mockSession.incoming } returns channel
        channel.close()

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertNull(result)
    }

    @Test
    fun wsSession_receiveNext_returnsTextMessage() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(1)
        channel.trySend(Frame.Text("hello"))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertEquals(WsClientExtension.WsMessage.Text("hello"), result)
    }

    @Test
    fun wsSession_receiveNext_returnsBinaryMessage() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(1)
        channel.trySend(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertTrue(result is WsClientExtension.WsMessage.Binary)
        assertTrue(result.bytes.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun wsSession_receiveNext_returnsCloseOnCloseFrame() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(1)
        channel.trySend(Frame.Close())
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertEquals(WsClientExtension.WsMessage.Close, result)
    }

    @Test
    fun wsSession_close_closesSession() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        coEvery { mockSession.send(any<Frame.Close>()) } just Runs

        val session = WsClientExtension.WsSession(mockSession)
        session.close()

        coVerify { mockSession.send(any<Frame.Close>()) }
    }

    @Test
    fun wsSession_receiveNext_fragmentedTextFrames_reassembledIntoSingleMessage() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(Channel.UNLIMITED)

        channel.trySend(Frame.Text(fin = false, data = "Hel".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = false, data = "lo ".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = false, data = "Ze".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = true, data = "ta".encodeToByteArray()))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertEquals(WsClientExtension.WsMessage.Text("Hello Zeta"), result)
    }

    @Test
    fun wsSession_receiveNext_multipleFragmentedMessages_eachReassembledIndependently() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(Channel.UNLIMITED)
        channel.trySend(Frame.Text(fin = false, data = "foo".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = true, data = "bar".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = false, data = "baz".encodeToByteArray()))
        channel.trySend(Frame.Text(fin = true, data = "qux".encodeToByteArray()))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)

        assertEquals(WsClientExtension.WsMessage.Text("foobar"), session.receiveNext())
        assertEquals(WsClientExtension.WsMessage.Text("bazqux"), session.receiveNext())
    }

    @Test
    fun wsSession_receiveNext_fragmentedBinaryFrames_reassembledIntoSingleMessage() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(Channel.UNLIMITED)
        channel.trySend(Frame.Binary(fin = false, data = byteArrayOf(1, 2, 3)))
        channel.trySend(Frame.Binary(fin = false, data = byteArrayOf(4, 5)))
        channel.trySend(Frame.Binary(fin = true, data = byteArrayOf(6)))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertTrue(result is WsClientExtension.WsMessage.Binary)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result.bytes)
    }

    @Test
    fun wsSession_receiveNext_singleFrameWithFinTrue_worksAsBeforeWithNoBehaviorChange() {
        val mockSession = mockk<DefaultClientWebSocketSession>()
        val channel = Channel<Frame>(Channel.UNLIMITED)

        channel.trySend(Frame.Text(fin = true, data = "normal".encodeToByteArray()))
        every { mockSession.incoming } returns channel

        val session = WsClientExtension.WsSession(mockSession)
        val result = session.receiveNext()

        assertEquals(WsClientExtension.WsMessage.Text("normal"), result)
    }
}
