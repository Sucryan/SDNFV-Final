package nycu.winlab.proxyndp;

import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.flow.DefaultTrafficSelector;
//import org.onosproject.net.flow.TrafficSelector;

import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.device.DeviceService;

import org.onlab.packet.Ethernet;
import org.onlab.packet.ARP;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.net.packet.DefaultOutboundPacket;

import org.onlab.packet.Ip6Address;
import org.onlab.packet.IPv6;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPacket;
import org.onlab.packet.ipv6.BaseOptions;
import org.onlab.packet.ndp.NeighborSolicitation;
import org.onlab.packet.ndp.NeighborAdvertisement;
import org.onlab.packet.ndp.NeighborDiscoveryOptions;


@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger("ProxyNDP");

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    private ApplicationId appId;
    private ProxyArpProcessor processor = new ProxyArpProcessor();

    private Map<Ip4Address, MacAddress> arpTable = new HashMap<>();
    private Map<Ip6Address, MacAddress> ndpTable = new HashMap<>();
    private Map<MacAddress, MacLocation> macTable = new HashMap<>();

    private static final Ip4Address SPEAKER_IP4 =
        Ip4Address.valueOf("192.168.70.13");

    private static final Ip6Address SPEAKER_IP6 =
        Ip6Address.valueOf("fd70::13");

    private static final Ip4Address INNER_IP4 =
        Ip4Address.valueOf("192.168.63.1");

    private static final Ip6Address INNER_IP6 =
        Ip6Address.valueOf("fd63::1");

    private static final MacAddress VIRTUAL_MAC =
        MacAddress.valueOf("02:aa:bb:cc:dd:13");

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.proxyndp");

        packetService.addProcessor(processor, PacketProcessor.director(2));
        arpTable.put(SPEAKER_IP4, VIRTUAL_MAC);
        ndpTable.put(SPEAKER_IP6, VIRTUAL_MAC);
        arpTable.put(INNER_IP4, VIRTUAL_MAC);
        ndpTable.put(INNER_IP6, VIRTUAL_MAC);

        // ARP
        packetService.requestPackets(
        DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_ARP) // 0x0806
        .build(), PacketPriority.REACTIVE, appId);

        // IPv6
        packetService.requestPackets(
        DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV6) // 0x86DD
        .build(), PacketPriority.REACTIVE, appId);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;

        // ARP
        packetService.cancelPackets(
        DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_ARP) // 0x0806
        .build(), PacketPriority.REACTIVE, appId);

        // IPv6
        packetService.cancelPackets(
        DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV6) // 0x86DD
        .build(), PacketPriority.REACTIVE, appId);

        log.info("Stopped");
    }

    private class ProxyArpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {

            ARP arp = (ARP) ethPkt.getPayload();
            short op = arp.getOpCode();

            Ip4Address senderIp = Ip4Address.valueOf(arp.getSenderProtocolAddress());
            MacAddress senderMac = MacAddress.valueOf(arp.getSenderHardwareAddress());
            Ip4Address targetIp = Ip4Address.valueOf(arp.getTargetProtocolAddress());

            DeviceId deviceId = pkt.receivedFrom().deviceId();
            PortNumber inPort = pkt.receivedFrom().port();

            if (!senderIp.equals(SPEAKER_IP4) && !senderIp.equals(INNER_IP4)) {
                arpTable.put(senderIp, senderMac);
            }
            macTable.put(senderMac, new MacLocation(deviceId, inPort));

            if (op == ARP.OP_REQUEST) {
                MacAddress knownMac = arpTable.get(targetIp);
                if (knownMac != null) {
                    log.info("ARP TABLE HIT. Requested MAC = {}", knownMac);
                    sendArpReply(deviceId, inPort, senderIp, senderMac, targetIp, knownMac);
                    context.block();
                } else {
                    log.info("ARP TABLE MISS. Send request to edge ports");
                    edgeflood(context);
                }
            } else if (op == ARP.OP_REPLY) {
                MacAddress requestedMac = MacAddress.valueOf(arp.getSenderHardwareAddress());
                MacAddress dstMac = MacAddress.valueOf(arp.getTargetHardwareAddress());
                MacLocation loc = macTable.get(dstMac);
                //log.info("RECV REPLY. Requested MAC = {}", requestedMac);
                if (loc != null) {
                    packetOutTo(context, loc.deviceId, loc.port);
                } else {
                    edgeflood(context);
                }
            }
        }


        if (ethPkt.getEtherType() != Ethernet.TYPE_IPV6) {
            return;
        }

        IPv6 ipv6 = (IPv6) ethPkt.getPayload();

        IPacket payload = ipv6.getPayload();
        while (payload instanceof BaseOptions) {
            payload = payload.getPayload();
        }

        if (!(payload instanceof ICMP6)) {
            return;
        }

        ICMP6 icmp6 = (ICMP6) payload;
        byte type = icmp6.getIcmpType();

        DeviceId deviceId = pkt.receivedFrom().deviceId();
        PortNumber inPort = pkt.receivedFrom().port();
        MacAddress srcMac = ethPkt.getSourceMAC();
        MacAddress dstMac = ethPkt.getDestinationMAC();


        if (type == ICMP6.NEIGHBOR_SOLICITATION) {
            NeighborSolicitation ns = (NeighborSolicitation) icmp6.getPayload();
            Ip6Address senderIp6 = Ip6Address.valueOf(ipv6.getSourceAddress());
            Ip6Address targetIp6 = Ip6Address.valueOf(ns.getTargetAddress());

            if (!senderIp6.equals(SPEAKER_IP6) && !senderIp6.equals(INNER_IP6)) {
                ndpTable.put(senderIp6, srcMac);
            }

            MacAddress knownMac = ndpTable.get(targetIp6);
            if (knownMac != null) {
                log.info("NDP TABLE HIT. Requested MAC = {}", knownMac);
                sendNaReply(deviceId, inPort,
                    senderIp6, srcMac,
                    targetIp6, knownMac
                );
                context.block();
            } else {
                log.info("NDP TABLE MISS. Send NDP Solicitation to edge ports");
                edgeflood(context);
            }
            return;
        }

        if (type == ICMP6.NEIGHBOR_ADVERTISEMENT) {
            NeighborAdvertisement na = (NeighborAdvertisement) icmp6.getPayload();
            Ip6Address senderIp6 = Ip6Address.valueOf(ipv6.getSourceAddress());
            if (!senderIp6.equals(SPEAKER_IP6) && !senderIp6.equals(INNER_IP6)) {
                ndpTable.put(senderIp6, srcMac);
            }
            MacLocation loc = macTable.get(ethPkt.getDestinationMAC());

            if (loc != null) {
                packetOutTo(context, loc.deviceId, loc.port);
            } else {
                edgeflood(context);
            }
            return;
        }

        context.block();
      }
    }

    private void edgeflood(PacketContext context) {
        InboundPacket in = context.inPacket();
        Ethernet eth = in.parsed();
        if (eth == null) {
            return;
        }

        Topology topo = topologyService.currentTopology();

        for (Device device : deviceService.getDevices()) {
            DeviceId deviceId = device.id();
            for (Port port : deviceService.getPorts(deviceId)) {
                if (!port.isEnabled()) {
                    continue;
                }

                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                if (topologyService.isInfrastructure(topo, cp)) {
                    continue;
                }
                if (cp.equals(in.receivedFrom())) {
                    continue;
                }

                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setOutput(port.number())
                        .build();

                OutboundPacket packet = new DefaultOutboundPacket(
                        deviceId,
                        treatment,
                        ByteBuffer.wrap(eth.serialize())
                );
                packetService.emit(packet);
            }
        }
    }

    private void sendArpReply(DeviceId deviceId, PortNumber outPort,
                          Ip4Address requesterIp, MacAddress requesterMac,
                          Ip4Address targetIp, MacAddress targetMac) {

        Ethernet ethReply = new Ethernet();
        ethReply.setSourceMACAddress(targetMac);
        ethReply.setDestinationMACAddress(requesterMac);
        ethReply.setEtherType(Ethernet.TYPE_ARP);

        ARP arpReply = new ARP();
        arpReply.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arpReply.setProtocolType(ARP.PROTO_TYPE_IP);
        arpReply.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
        arpReply.setProtocolAddressLength((byte) 4);
        arpReply.setOpCode(ARP.OP_REPLY);

        arpReply.setSenderHardwareAddress(targetMac.toBytes());
        arpReply.setSenderProtocolAddress(targetIp.toOctets());
        arpReply.setTargetHardwareAddress(requesterMac.toBytes());
        arpReply.setTargetProtocolAddress(requesterIp.toOctets());

        ethReply.setPayload(arpReply);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        OutboundPacket packet = new DefaultOutboundPacket(
                deviceId,
                treatment,
                ByteBuffer.wrap(ethReply.serialize())
        );

        packetService.emit(packet);
    }


    private void sendNaReply(DeviceId deviceId, PortNumber outPort,
                         Ip6Address requesterIp, MacAddress requesterMac,
                         Ip6Address targetIp,    MacAddress targetMac) {

        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(targetMac);
        eth.setDestinationMACAddress(requesterMac);
        eth.setEtherType(Ethernet.TYPE_IPV6);

        IPv6 ipv6 = new IPv6();
        ipv6.setSourceAddress(targetIp.toOctets());
        ipv6.setDestinationAddress(requesterIp.toOctets());
        ipv6.setNextHeader(IPv6.PROTOCOL_ICMP6);
        ipv6.setHopLimit((byte) 255);

        ICMP6 icmp6 = new ICMP6();
        icmp6.setIcmpType(ICMP6.NEIGHBOR_ADVERTISEMENT);
        icmp6.setIcmpCode((byte) 0);

        NeighborAdvertisement na = new NeighborAdvertisement();
        na.setTargetAddress(targetIp.toOctets());
        na.setSolicitedFlag((byte) 1);
        na.setOverrideFlag((byte) 1);
        na.setRouterFlag((byte) 0);

        na.addOption(NeighborDiscoveryOptions.TYPE_TARGET_LL_ADDRESS,
                 targetMac.toBytes());

        icmp6.setPayload(na);
        ipv6.setPayload(icmp6);
        eth.setPayload(ipv6);


        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setOutput(outPort)
            .build();

        OutboundPacket packet = new DefaultOutboundPacket(
            deviceId, treatment, ByteBuffer.wrap(eth.serialize()));
        packetService.emit(packet);
    }



    private void packetOutTo(PacketContext context, DeviceId deviceId, PortNumber outPort) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        OutboundPacket packet = new DefaultOutboundPacket(
                deviceId,
                treatment,
                context.inPacket().unparsed()
        );
        packetService.emit(packet);
    }

    private static class MacLocation {
        DeviceId deviceId;
        PortNumber port;

        MacLocation(DeviceId deviceId, PortNumber port) {
            this.deviceId = deviceId;
            this.port = port;
        }
    }

}

