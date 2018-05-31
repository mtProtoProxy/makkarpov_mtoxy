package ru.makkarpov.mtoxy.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.makkarpov.mtoxy.MTServer;
import ru.makkarpov.mtoxy.stats.ConnectionType;
import ru.makkarpov.mtoxy.util.PeerRecord;

import java.util.List;

/**
 * A handler that will connect to specified Telegram datacenter upon reception of a HandshakeMessage from Obfuscated2
 * codec and set up connection forwarding afterwards.
 */
public class DatacenterConnectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(DatacenterConnectionHandler.class);

    private MTServer server;
    private CompositeByteBuf awaitingMessages;

    public DatacenterConnectionHandler(MTServer server) {
        this.server = server;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        awaitingMessages = ctx.alloc().compositeBuffer();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Obfuscated2Handshaker.HandshakeCompletedMessage) {
            // Suspend reading until we will establish connection.
            // Don't remove ourselves so pending messages will be buffered.
            ctx.channel().config().setAutoRead(false);

            int dcNumber = ((Obfuscated2Handshaker.HandshakeCompletedMessage) msg).getDatacenterNumber();
            List<PeerRecord> peers = server.getConfiguration().getPeers();
            int peerNumber = (Math.abs(dcNumber) - 1) % peers.size();
            PeerRecord peer = peers.get(peerNumber);
            Obfuscated2Handshaker handshaker = Obfuscated2Handshaker.fromPeer(peer, dcNumber);

            ChannelFuture future = server.getBootstrap(peer.getAddress())
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(handshaker);
                        }
                    })
                    .connect();

            future.addListener(f -> {
                if (future.isSuccess()) {
                    // Wait for handshake:
                    handshaker.getHandshakePromise().addListener(f1 -> {
                        Channel ch = future.channel();

                        // Are we still connected?
                        if (!ctx.channel().isRegistered()) {
                            ch.close();
                            return;
                        }

                        if (f1.isSuccess()) {
                            // Setup forwarding and resume reading
                            ForwardingHandler.setupForwarding(ctx.channel(), ch);
                            ctx.channel().pipeline().remove(DatacenterConnectionHandler.this);
                            // Inject awaiting messages right after codec:
                            ctx.channel().pipeline().context(Obfuscated2Codec.class).fireChannelRead(awaitingMessages);
                            ctx.channel().config().setAutoRead(true);
                        }
                    });
                } else {
                    LOG.error("Failed to connect to peer: {} -> {}", ctx.channel().remoteAddress(),
                            peer.getAddress(), future.cause());
                    ctx.channel().close();
                    server.getStatisticsTracker().connectionFailed(ConnectionType.MTPROTO);
                }
            });
        } else if (msg instanceof ByteBuf) {
            awaitingMessages.addComponent(true, (ByteBuf) msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.error("Exception caught in datacenter connection handler from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
