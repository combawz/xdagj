/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.discovery;

import com.google.common.annotations.VisibleForTesting;
import io.libp2p.core.crypto.KEY_TYPE;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.xdag.Kernel;
import io.xdag.discovery.data.Packet;
import io.xdag.discovery.data.PacketData;
import io.xdag.discovery.data.PacketType;
import io.xdag.discovery.message.FindNeighborsPacketData;
import io.xdag.discovery.message.NeighborsPacketData;
import io.xdag.discovery.message.PingPacketData;
import io.xdag.discovery.message.PongPacketData;
import io.xdag.discovery.peers.DiscoveryPeer;
import io.xdag.discovery.peers.Endpoint;
import io.xdag.discovery.peers.Peer;
import io.xdag.discovery.peers.PeerTable;
import io.xdag.utils.discoveryutils.PeerDiscoveryPacketDecodingException;
import io.xdag.utils.discoveryutils.PeerDiscoveryStatus;
import io.xdag.utils.discoveryutils.Subscribers;
import io.xdag.utils.discoveryutils.bytes.BytesValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.tuweni.bytes.Bytes;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.xdag.utils.discoveryutils.Preconditions.checkGuard;
import static io.xdag.utils.discoveryutils.bytes.MutableBytesValue.wrapBuffer;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
public class DiscoveryController {
    private boolean isbootnode;
    private static final int MAX_PACKET_SIZE_BYTES = 1600;
    private PeerTable peerTable;
    private DatagramSocket socket;
    private long lastRefreshTime = -1;
    private DiscoveryPeer myself;
    private Vertx vertx;
    private Endpoint mynode;
    private List<DiscoveryPeer> bootnodes;
    private PrivKey privKey;
    private final Kernel kernel;
    private static final long REFRESH_CHECK_INTERVAL_MILLIS = MILLISECONDS.convert(30, SECONDS);
    private final long tableRefreshIntervalMs = MILLISECONDS.convert(30, SECONDS);
    private final RetryDelayFunction retryDelayFunction = RetryDelayFunction.linear(1.5, 2000, 60000);
    private final Map<BytesValue, PeerInteractionState> inflightInteractions =
            new ConcurrentHashMap<>();
    private final Subscribers<Consumer<PeerDiscoveryEvent.PeerBondedEvent>> peerBondedObservers = new Subscribers<>();

    public DiscoveryController(Kernel kernel) {
        this.kernel = kernel;
    }

    public void start() throws DecoderException, IOException {
        this.isbootnode = kernel.getConfig().isbootnode;
        final BytesValue myid ;
        if(isbootnode){
            log.debug("seed node start");
            String prikey = this.kernel.getConfig().getPrivkey();
            // id = 08021221027611680ca65e8fb7214a31b6ce6fcd8e6fe6a5f4d784dc6601dfe2bb9f8c96c2
            this.privKey = KeyKt.unmarshalPrivateKey(Bytes.fromHexString(prikey).toArrayUnsafe());
            myid = BytesValue.wrap(privKey.publicKey().bytes());
        }else {
            //gen random nodeid
            this.privKey = KeyKt.generateKeyPair(KEY_TYPE.SECP256K1).getFirst();
            myid = BytesValue.wrap(privKey.publicKey().bytes());
        }
        mynode = new Endpoint(this.kernel.getConfig().getNodeIp(),
                this.kernel.getConfig().discoveryPort,OptionalInt.of(this.kernel.getConfig().getLibp2pPort()));
        myself = new DiscoveryPeer(myid,mynode);
        peerTable = new PeerTable(myid, 16);
        bootnodes = this.kernel.getConfig().getBootnode();
        log.debug("bootnodes {}", bootnodes.toString());
        this.vertx = Vertx.vertx();
        vertx.createDatagramSocket().listen(mynode.getUdpPort(),mynode.getHost(), ar -> {
            if (!ar.succeeded()) {
                log.debug("seed node init fail");
            } else {
                log.debug("seed node init success");
            }
            socket = ar.result();
            socket.exceptionHandler(this::handleException);
            socket.handler(this::handlePacket);
        });

        // 定时执行的线程
        timeRefreshTable();

        if(!isbootnode){
            //先把种子节点加入peerTable里面,所以没有下面的添加成功 ADDED表示添加成功
            bootnodes.stream().filter(node -> {
                try {
                    return peerTable.tryAdd(node).getOutcome() == PeerTable.AddResult.Outcome.ADDED;
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
                return false;
            }).forEach(node -> bond(node, true));
        }
    }

    @VisibleForTesting
    void bond(final DiscoveryPeer peer, final boolean bootstrap) {
        peer.setFirstDiscovered(System.currentTimeMillis());
        peer.setStatus(PeerDiscoveryStatus.BONDING);

        final Consumer<PeerInteractionState> action =
                interaction -> {
                    final PingPacketData data =
                            PingPacketData.create(myself.getEndpoint(), peer.getEndpoint());
                    Packet sentPacket = null;
                    try {
                        sentPacket = sendPacket(peer, PacketType.PING, data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    final BytesValue pingHash = sentPacket.getHash();
                    // Update the matching filter to only accept the PONG if it echoes the hash of our PING.
                    final Predicate<Packet> newFilter =
                            packet ->
                                    packet
                                            .getPacketData(PongPacketData.class)
                                            .map(pong -> pong.getPingHash().equals(pingHash))
                                            .orElse(false);
                    interaction.updateFilter(newFilter);
                };

        // The filter condition will be updated as soon as the action is performed.
        final PeerInteractionState ping =
                new PeerInteractionState(action, PacketType.PONG, (packet) -> false, true, bootstrap);
        dispatchInteraction(peer, ping);
    }

    public Packet sendPacket(final DiscoveryPeer peer, final PacketType type, final PacketData data) throws IOException {


        final Packet packet = Packet.create(type , data, privKey);
        socket.send(
                packet.encode(),
                peer.getEndpoint().getUdpPort(),
                peer.getEndpoint().getHost(),
                ar -> {
                    if (ar.failed()) {
                        log.warn(
                                "Sending to peer {} failed, packet: {}",
                                peer,
                                wrapBuffer(packet.encode()),
                                ar.cause());
                        return;
                    }
                    if (ar.succeeded()) {
                        peer.setLastContacted(System.currentTimeMillis());
                    }
                });

        return packet;
    }

    private void dispatchInteraction(final Peer peer, final PeerInteractionState state) {
        final PeerInteractionState previous = inflightInteractions.put(peer.getId(), state);
        log.info("inflightInteractions.put {} " ,peer.getId());
        if (previous != null) {
            previous.cancelTimers();
        }
        state.execute(0);
    }
    private void handleException(final Throwable exception) {
        if (exception instanceof IOException) {
            log.debug("Packet handler exception", exception);
        } else {
            log.error("Packet handler exception", exception);
        }
    }
    // 30s询问一次时间是否够半小时 半小时刷新一次refreshTable
    public void timeRefreshTable() {
        log.info("start RefreshTable");
        vertx.setPeriodic(
                Math.min(REFRESH_CHECK_INTERVAL_MILLIS, tableRefreshIntervalMs),
                (l) -> {
                    try {
                        refreshTableIfRequired();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }

    private void refreshTableIfRequired() throws IOException {
        final long now = System.currentTimeMillis();
        if (lastRefreshTime + tableRefreshIntervalMs < now) {
            log.info("Peer table refresh triggered by timer expiry");
            refreshTable();
        }
    }

    private void refreshTable() throws IOException {
        final BytesValue target = Peer.randomId();
        peerTable.nearestPeers(Peer.randomId(), 16).forEach((peer) -> findNodes(peer, target));
        lastRefreshTime = System.currentTimeMillis();
    }
    private void findNodes(final DiscoveryPeer peer, final BytesValue target) {
        final Consumer<PeerInteractionState> action =
                (interaction) -> {
                    final FindNeighborsPacketData data = FindNeighborsPacketData.create(target);
                    try {
                        sendPacket(peer, PacketType.FIND_NEIGHBORS, data);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                };
        final PeerInteractionState interaction =
                new PeerInteractionState(action, PacketType.NEIGHBORS, packet -> true, true, false);
        dispatchInteraction(peer, interaction);
    }

    private void handlePacket(final DatagramPacket datagram) {
        try {
            final int length = datagram.data().length();
            checkGuard(
                    length <= MAX_PACKET_SIZE_BYTES,
                    PeerDiscoveryPacketDecodingException::new,
                    "Packet too large. Actual size (bytes): %s",
                    length);

            // We allow exceptions to bubble up, as they'll be picked up by the exception handler.
//            System.out.println("privKey.publicKey().bytes() = " + Hex.toHexString(privKey.publicKey().bytes()));
            final Packet packet = Packet.decode(datagram.data());

            OptionalInt fromPort = OptionalInt.empty();
            if (packet.getPacketData(PingPacketData.class).isPresent()) {
                final PingPacketData ping = packet.getPacketData(PingPacketData.class).orElseGet(null);
                if (ping != null && ping.getFrom() != null && ping.getFrom().getTcpPort().isPresent()) {
                    fromPort = ping.getFrom().getTcpPort();
                }
            }

            // Acquire the senders coordinates to build a Peer representation from them.
            final String addr = datagram.sender().host();
            final int port = datagram.sender().port();

            // Notify the peer controller.
            final DiscoveryPeer peer = new DiscoveryPeer(packet.getNodeId(), addr, port, fromPort);
//            System.out.println("packet.getNodeId() = "+ packet.getNodeId());
            onMessage(packet, peer);
        } catch (final PeerDiscoveryPacketDecodingException e) {
            log.debug("Discarding invalid peer discovery packet", e);
        } catch (final Throwable t) {
            log.error("Encountered error while handling packet", t);
        }
    }

    public void onMessage(final Packet packet, final DiscoveryPeer sender) throws IOException {
        log.info("Received Packet Type {}",packet.getType());
        // Message from self. This should not happen.
        if (sender.getId().equals(myself.getId())) {
            log.info("发送人是自己");
            return;
        }

        // Load the peer from the table, or use the instance that comes in.
        final Optional<DiscoveryPeer> maybeKnownPeer = peerTable.get(sender);
        final DiscoveryPeer peer = maybeKnownPeer.orElse(sender);

        switch (packet.getType()) {
            case PING:
                if (addToPeerTable(peer)) {
                    log.info("Table成功添加peer");
                    final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
                    respondToPing(ping, packet.getHash(), peer);
                }

                break;
            case PONG:
            {
                matchInteraction(packet)
                        .ifPresent(
                                interaction -> {
                                    log.info("Table成功添加peer");
                                    try {
                                        addToPeerTable(peer);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }

                                    // If this was a bootstrap peer, let's ask it for nodes near to us.
                                    // 如果是种子节点就询问对方离我最近的节点
                                    if (interaction.isBootstrap()) {
                                        log.info("interaction.isBootstrap() = {}" ,interaction.isBootstrap());
                                        findNodes(peer, myself.getId());
                                    }
                                });
                break;
            }
            case NEIGHBORS:

                matchInteraction(packet)
                        .ifPresent(
                                interaction -> {
                                    // Extract the peers from the incoming packet.
                                    log.info("enter NEIGHBORS");
                                    final List<DiscoveryPeer> neighbors =
                                            packet
                                                    .getPacketData(NeighborsPacketData.class)
                                                    .map(NeighborsPacketData::getNodes)
                                                    .orElse(emptyList());
                                    //向peerTable没有的peer发送ping消息
                                    for (final DiscoveryPeer neighbor : neighbors) {
                                        System.out.println("neighbors.size() = " + neighbors.size());
                                        try {
                                            if (peerTable.get(neighbor).isPresent() || myself.getId().equals(neighbor.getId())) {
                                                log.info("peerTable had this peer or not ping myself");
                                                continue;
                                            }
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        //todo
                                        log.info("bond new peer and neighbor.getId() ="+ neighbor.getId());
                                        bond(neighbor, false);
                                    }
                                });
                break;

            case FIND_NEIGHBORS:
                final FindNeighborsPacketData fn =
                        packet.getPacketData(FindNeighborsPacketData.class).get();
                respondToFindNeighbors(fn, peer);
                break;
        }
    }

    private void respondToPing(
            final PingPacketData packetData, final BytesValue pingHash, final DiscoveryPeer sender) throws IOException {
        final PongPacketData data = PongPacketData.create(packetData.getFrom(), pingHash);
        sendPacket(sender, PacketType.PONG, data);
    }
    private void respondToFindNeighbors(
            final FindNeighborsPacketData packetData, final DiscoveryPeer sender) throws IOException {
        // TODO: for now return 16 peers. Other implementations calculate how many
        // peers they can fit in a 1280-byte payload.
        final List<DiscoveryPeer> peers = peerTable.nearestPeers(packetData.getTarget(), 16);
        log.info("DiscoveryPeer number {}",peers.size());
        final PacketData data = NeighborsPacketData.create(peers);
        sendPacket(sender, PacketType.NEIGHBORS, data);
    }

    private boolean addToPeerTable(final DiscoveryPeer peer) throws IOException {
        final PeerTable.AddResult result = peerTable.tryAdd(peer);
        if (result.getOutcome() == PeerTable.AddResult.Outcome.SELF) {
            return false;
        }

        // Reset the last seen timestamp.
        final long now = System.currentTimeMillis();
        if (peer.getFirstDiscovered() == 0) {
            peer.setFirstDiscovered(now);
        }
        peer.setLastSeen(now);

        if (peer.getStatus() != PeerDiscoveryStatus.BONDED) {
            peer.setStatus(PeerDiscoveryStatus.BONDED);
            notifyPeerBonded(peer, now);
        }

        if (result.getOutcome() == PeerTable.AddResult.Outcome.ALREADY_EXISTED) {
            // Bump peer.
            peerTable.evict(peer);
            peerTable.tryAdd(peer);
        } else if (result.getOutcome() == PeerTable.AddResult.Outcome.BUCKET_FULL) {
            peerTable.evict(result.getEvictionCandidate());
            peerTable.tryAdd(peer);
        }

        return true;
    }

    private void notifyPeerBonded(final DiscoveryPeer peer, final long now) {
        final PeerDiscoveryEvent.PeerBondedEvent event = new PeerDiscoveryEvent.PeerBondedEvent(peer, now);
        dispatchEvent(peerBondedObservers, event);
    }
    public Endpoint getMynode(){
        return mynode;
    }

    public PeerTable getPeerTable(){
        return peerTable;
    }

    public CompletableFuture<?> stop() {
        if (socket == null) {
            return CompletableFuture.completedFuture(null);
        }

        final CompletableFuture<?> completion = new CompletableFuture<>();
        socket.close(
                ar -> {
                    if (ar.succeeded()) {
                        inflightInteractions.values().forEach(PeerInteractionState::cancelTimers);
                        inflightInteractions.clear();
                        socket = null;
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(ar.cause());
                    }
                });
        return completion;
    }

    private class PeerInteractionState implements Predicate<Packet> {
        /**
         * The action that led to the peer being in this state (e.g. sending a PING or NEIGHBORS
         * message), in case it needs to be retried.
         */
        private final Consumer<PeerInteractionState> action;
        /**
         * The expected type of the message that will transition the peer out of this state.
         */
        private final PacketType expectedType;
        /**
         * A custom filter to accept transitions out of this state.
         */
        private Predicate<Packet> filter;
        /**
         * Whether the action associated to this state is retryable or not.
         */
        private final boolean retryable;
        /**
         * Whether this is an entry for a bootstrap peer.
         */
        private final boolean bootstrap;
        /**
         * Timers associated with this entry.
         */
        private OptionalLong timerId = OptionalLong.empty();

        PeerInteractionState(
                final Consumer<PeerInteractionState> action,
                final PacketType expectedType,
                final Predicate<Packet> filter,
                final boolean retryable,
                final boolean bootstrap) {
            this.action = action;
            this.expectedType = expectedType;
            this.filter = filter;
            this.retryable = retryable;
            this.bootstrap = bootstrap;
        }

        @Override
        public boolean test(final Packet packet) {
            return expectedType == packet.getType() && (filter == null || filter.test(packet));
        }

        void updateFilter(final Predicate<Packet> filter) {
            this.filter = filter;
        }

        boolean isBootstrap() {
            return bootstrap;
        }

        /**
         * Executes the action associated with this state. Sets a "boomerang" timer to itself in case
         * the action is retryable.
         *
         * @param lastTimeout the previous timeout, or 0 if this is the first time the action is being
         *                    executed.
         */
        void execute(final long lastTimeout) {
            action.accept(this);
            if (retryable) {
                final long newTimeout = retryDelayFunction.apply(lastTimeout);
                timerId = OptionalLong.of(vertx.setTimer(newTimeout, id -> execute(newTimeout)));
            }
        }


        void cancelTimers() {
            timerId.ifPresent(vertx::cancelTimer);
        }
    }
    private <T extends PeerDiscoveryEvent> void dispatchEvent(
            final Subscribers<Consumer<T>> observers, final T event) {
        observers.forEach(
                observer ->
                        vertx.executeBlocking(
                                future -> {
                                    observer.accept(event);
                                    future.complete();
                                },
                                x -> {}));
    }
    private Optional<PeerInteractionState> matchInteraction(final Packet packet) {
        final PeerInteractionState interaction = inflightInteractions.get(packet.getNodeId());
        log.info("packet.getNodeId() = {}",packet.getNodeId());
        if (interaction == null || !interaction.test(packet)) {
            log.info("interaction == null = {}",interaction == null);
            log.info("return Optional.empty()");
            return Optional.empty();
        }
        System.out.println("互动匹配");
        interaction.cancelTimers();
        inflightInteractions.remove(packet.getNodeId());
        return Optional.of(interaction);
    }
}

