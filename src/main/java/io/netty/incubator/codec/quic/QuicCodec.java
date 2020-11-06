/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// TODO: - Handle connect
//       - Handle timeout
//       - Handle flush correctly
public final class QuicCodec extends ChannelInboundHandlerAdapter {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(QuicCodec.class);

    private ByteBuf versionBuffer;
    private ByteBuf typeBuffer;
    private ByteBuf scidLenBuffer;
    private ByteBuf scidBuffer;
    private ByteBuf dcidLenBuffer;
    private ByteBuf dcidBuffer;
    private ByteBuf tokenBuffer;
    private ByteBuf tokenLenBuffer;

    private final Map<ByteBuffer, QuicheQuicChannel> connections = new HashMap<>();
    private final long config;

    private final ChannelHandler childHandler;
    private final QuicConnectionIdSigner connectionSigner;
    private final QuicTokenHandler tokenHandler;

    QuicCodec(long config, QuicTokenHandler tokenHandler,
              QuicConnectionIdSigner connectionSigner, ChannelHandler childHandler) {
        this.config = config;
        this.tokenHandler = tokenHandler;
        this.connectionSigner = connectionSigner;
        this.childHandler = childHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        versionBuffer = allocateNativeOrder(Integer.BYTES);
        typeBuffer = allocateNativeOrder(Byte.BYTES);
        scidBuffer = allocateNativeOrder(Quiche.QUICHE_MAX_CONN_ID_LEN);
        scidLenBuffer = allocateNativeOrder(Integer.BYTES);
        dcidBuffer = allocateNativeOrder(Quiche.QUICHE_MAX_CONN_ID_LEN);
        dcidLenBuffer = allocateNativeOrder(Integer.BYTES);
        tokenBuffer = allocateNativeOrder(tokenHandler.maxTokenLength());
        tokenLenBuffer = allocateNativeOrder(Integer.BYTES);
    }

    @SuppressWarnings("deprecation")
    private static ByteBuf allocateNativeOrder(int capacity) {
        // Just use Unpooled as the life-time of these buffers is long.
        ByteBuf buffer = Unpooled.directBuffer(capacity);

        // As we use the buffers as pointers to int etc we need to ensure we use the right oder so we will
        // see the right value when we read primitive values.
        return PlatformDependent.BIG_ENDIAN_NATIVE_ORDER ? buffer : buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        Quiche.quiche_config_free(config);

        versionBuffer.release();
        tokenBuffer.release();
        scidBuffer.release();
        scidLenBuffer.release();
        dcidBuffer.release();
        dcidLenBuffer.release();
        tokenLenBuffer.release();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        DatagramPacket packet = (DatagramPacket) msg;
        try {
            ByteBuf buffer = ((DatagramPacket) msg).content();
            long contentAddress = buffer.memoryAddress() + buffer.readerIndex();
            int contentReadable = buffer.readableBytes();

            // Ret various len values so quiche_header_info can make use of these.
            scidLenBuffer.setInt(0, Quiche.QUICHE_MAX_CONN_ID_LEN);
            dcidLenBuffer.setInt(0, Quiche.QUICHE_MAX_CONN_ID_LEN);
            tokenLenBuffer.setInt(0, tokenHandler.maxTokenLength());

            int res = Quiche.quiche_header_info(contentAddress, contentReadable, Quiche.QUICHE_MAX_CONN_ID_LEN,
                    versionBuffer.memoryAddress(), typeBuffer.memoryAddress(),
                    scidBuffer.memoryAddress(), scidLenBuffer.memoryAddress(),
                    dcidBuffer.memoryAddress(), dcidLenBuffer.memoryAddress(),
                    tokenBuffer.memoryAddress(), tokenLenBuffer.memoryAddress());
            if (res >= 0) {
                int version = versionBuffer.getInt(0);
                byte type = typeBuffer.getByte(0);
                int scidLen = scidLenBuffer.getInt(0);
                int dcidLen = dcidLenBuffer.getInt(0);
                int tokenLen = tokenLenBuffer.getInt(0);

                quicPacketRead(ctx, packet, type, version, scidBuffer.setIndex(0, scidLen),
                        dcidBuffer.setIndex(0, dcidLen), tokenBuffer.setIndex(0, tokenLen));
            } else {
                LOGGER.debug("Unable to parse QUIC header via quiche_header_info: {}", Quiche.errorAsString(res));
            }
        } finally {
            packet.release();
        }
    }

    private void quicPacketRead(ChannelHandlerContext ctx, DatagramPacket packet, byte type, int version,
                                ByteBuf scid, ByteBuf dcid, ByteBuf token) throws Exception {
        ByteBuffer dcidByteBuffer = dcid.internalNioBuffer(dcid.readerIndex(), dcid.readableBytes());

        QuicheQuicChannel channel = connections.get(dcidByteBuffer);
        if (channel == null) {
            final ByteBuffer connId = ByteBuffer.wrap(connectionSigner.sign(dcid));

            channel = connections.get(connId);
            if (channel == null) {
                if (!Quiche.quiche_version_is_supported(version)) {
                    // Version is not supported, try to negotiate it.
                    ByteBuf out = ctx.alloc().directBuffer(Quic.MAX_DATAGRAM_SIZE);
                    int outWriterIndex = out.writerIndex();

                    int res = Quiche.quiche_negotiate_version(
                            scid.memoryAddress() + scid.readerIndex(), scid.readableBytes(),
                            dcid.memoryAddress() + dcid.readerIndex(), dcid.readableBytes(),
                            out.memoryAddress() + outWriterIndex, out.writableBytes());
                    if (res < 0) {
                        out.release();
                        if (res != Quiche.QUICHE_ERR_DONE) {
                            throw Quiche.newException(res);
                        }
                        return;
                    }

                    ctx.writeAndFlush(new DatagramPacket(out.writerIndex(outWriterIndex + res), packet.sender()));
                    return;
                }

                if (!token.isReadable()) {
                    // The remote peer did not send a token.
                    ByteBuf mintTokenBuffer = ctx.alloc().directBuffer(tokenHandler.maxTokenLength());

                    tokenHandler.writeToken(mintTokenBuffer, dcid, packet.sender());

                    ByteBuf connIdBuffer = ctx.alloc().directBuffer(connId.limit());
                    connIdBuffer.writeBytes(connId);

                    ByteBuf out = ctx.alloc().directBuffer(Quic.MAX_DATAGRAM_SIZE);
                    int outWriterIndex = out.writerIndex();
                    int written = Quiche.quiche_retry(scid.memoryAddress() + scid.readerIndex(), scid.readableBytes(),
                            dcid.memoryAddress() + dcid.readerIndex(), dcid.readableBytes(),
                            connIdBuffer.memoryAddress() + connIdBuffer.readerIndex(), connIdBuffer.readableBytes(),
                            mintTokenBuffer.memoryAddress() + mintTokenBuffer.readerIndex(),
                            mintTokenBuffer.readableBytes(),
                            version, out.memoryAddress() + outWriterIndex, out.writableBytes());

                    //mintTokenBuffer.release();

                    if (written < 0) {
                        out.release();
                    } else {
                        ctx.writeAndFlush(new DatagramPacket(out.writerIndex(outWriterIndex + written),
                                packet.sender()));
                    }
                    return;
                }
                int offset = tokenHandler.validateToken(token, packet.sender());
                if (offset == -1) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("invalid token: {}", token.toString(CharsetUtil.US_ASCII));
                    }
                    return;
                }

                if (connId.limit() != dcidBuffer.readableBytes()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("invalid destination connection id: {}",
                                dcidBuffer.toString(CharsetUtil.US_ASCII));
                    }
                    return;
                }

                long conn = Quiche.quiche_accept(dcid.memoryAddress() + dcid.readerIndex(), dcid.readableBytes(),
                        token.memoryAddress() + offset, token.readableBytes() - offset, config);
                if (conn < 0) {
                    LOGGER.debug("quiche_accept failed");
                    return;
                }
                channel = new QuicheQuicChannel(conn, ctx, packet.sender(), childHandler);
                connections.put(connId, channel);
                ctx.channel().eventLoop().register(channel);
            }
        }

        channel.recv(packet.content());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        boolean writeDone = false;
        Iterator<Map.Entry<ByteBuffer, QuicheQuicChannel>> entries = connections.entrySet().iterator();
        while (entries.hasNext()) {
            QuicheQuicChannel channel = entries.next().getValue();
            // TODO: Be a bit smarter about this.
            channel.fireChannelReadCompleteIfNeeded();

            writeDone |= channel.flushEgress();
            if (channel.freeIfClosed()) {
                entries.remove();
            }
        }
        if (writeDone) {
            ctx.flush();
        }
    }
}