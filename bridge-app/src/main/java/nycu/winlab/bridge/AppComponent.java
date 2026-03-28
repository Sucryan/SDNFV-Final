package nycu.winlab.bridge;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPacket;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.MacAddress;

import org.onlab.packet.ipv6.BaseOptions;
import org.onlab.packet.ndp.NeighborAdvertisement;
import org.onlab.packet.ndp.NeighborDiscoveryOptions;
import org.onlab.packet.ndp.NeighborSolicitation;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;

import org.onosproject.net.device.DeviceService;

import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;

import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.onlab.util.Tools.get;

import org.onlab.packet.IPv4;
import org.onosproject.routeservice.Route;
import org.onosproject.routeservice.RouteService;

import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigEvent;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger("bridgeApp");

    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfgService;

    private volatile boolean configReady = false;

    private ApplicationId appId;
    private final LearningBridgeProcessor processor = new LearningBridgeProcessor();

    private static final MacAddress FRR_MAC =
            MacAddress.valueOf("46:bb:86:18:2c:47");
    private static final MacAddress VIRTUAL_MAC =
            MacAddress.valueOf("02:aa:bb:cc:dd:13");
    private static final MacAddress TA_ROUTER_MAC =
            MacAddress.valueOf("0e:a7:1a:c5:29:15");
    private final Map<DeviceId, Map<MacAddress, PortNumber>> bridgeTable = new HashMap<>();

    private final Map<Ip4Address, MacAddress> arpTable = new HashMap<>();
    private final Map<Ip6Address, MacAddress> ndpTable = new HashMap<>();
    private final Map<MacAddress, MacLocation> macTable = new HashMap<>();

    private static final Ip4Address SPEAKER_IP4 =
            Ip4Address.valueOf("192.168.70.13");
    private static final Ip6Address SPEAKER_IP6 =
            Ip6Address.valueOf("fd70::13");

    private static final Ip4Address INNER_IP4 =
            Ip4Address.valueOf("192.168.63.1");
    private static final Ip6Address INNER_IP6 =
            Ip6Address.valueOf("fd63::1");
    private static final Ip4Address FRR_GATEWAY_IP4 =
            Ip4Address.valueOf("172.16.13.1");
    private static final Ip6Address FRR_GATEWAY_IP6 =
            Ip6Address.valueOf("2a0b:4e07:c4:13::1");

    private DeviceId ovs2;
    private DeviceId ovs3;

    private static final PortNumber OVS2_VXLAN_PORT = PortNumber.portNumber(3);
    private static final PortNumber OVS3_TO_TA_PORT = PortNumber.portNumber(3);
    private static final PortNumber OVS2_TO_PEER14 = PortNumber.portNumber(4);
    private static final PortNumber OVS2_TO_PEER15 = PortNumber.portNumber(5);

    private final ConfigFactory<ApplicationId, OvsConfig> ovsConfigFactory =
            new ConfigFactory<ApplicationId, OvsConfig>(
                    APP_SUBJECT_FACTORY,
                    OvsConfig.class,
                    "bridge-ovs") {

                @Override
                public OvsConfig createConfig() {
                    return new OvsConfig();
                }
            };

    private final InternalConfigListener cfgListener =
            new InternalConfigListener();

    private class InternalConfigListener implements NetworkConfigListener {

        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                    && event.configClass().equals(OvsConfig.class)) {

                OvsConfig cfg = netcfgService.getConfig(appId, OvsConfig.class);
                if (cfg == null) {
                    return;
                }

                ovs2 = cfg.ovs2();
                ovs3 = cfg.ovs3();

                log.info("Bridge OVS config received: ovs2={}, ovs3={}", ovs2, ovs3);

                configReady = true;

                log.info("Bridge app is now READY");
            }
        }
    }

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.bridge");

        configReady = false;

        netcfgService.registerConfigFactory(ovsConfigFactory);
        netcfgService.addListener(cfgListener);

        OvsConfig cfg = netcfgService.getConfig(appId, OvsConfig.class);
        if (cfg != null) {
            ovs2 = cfg.ovs2();
            ovs3 = cfg.ovs3();

            configReady = true;
            log.info("Bridge OVS config loaded at activate");
        } else {
            log.info("Bridge app started, waiting for OVS config...");
        }

        packetService.addProcessor(processor, PacketProcessor.director(3));

        arpTable.put(SPEAKER_IP4, VIRTUAL_MAC);
        ndpTable.put(SPEAKER_IP6, VIRTUAL_MAC);
        arpTable.put(INNER_IP4, VIRTUAL_MAC);
        ndpTable.put(INNER_IP6, VIRTUAL_MAC);
        arpTable.put(FRR_GATEWAY_IP4, VIRTUAL_MAC);
        ndpTable.put(FRR_GATEWAY_IP6, VIRTUAL_MAC);


        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);

        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV6).build(),
                PacketPriority.REACTIVE, appId);

        packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
    configReady = false;

    netcfgService.removeListener(cfgListener);
    netcfgService.unregisterConfigFactory(ovsConfigFactory);

        flowRuleService.removeFlowRulesById(appId);

        packetService.removeProcessor(processor);

        packetService.cancelPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
                PacketPriority.REACTIVE, appId);

        packetService.cancelPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV6).build(),
                PacketPriority.REACTIVE, appId);

        packetService.cancelPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                PacketPriority.REACTIVE, appId);

        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured: someProperty={}", someProperty);
    }


    private class LearningBridgeProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (!configReady) {
                context.block();
                return;
            }

            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            short et = ethPkt.getEtherType();

            if (et == Ethernet.TYPE_LLDP) {
                return;
            }

            if (et == Ethernet.TYPE_ARP) {
                handleArpProxy(context, pkt, ethPkt);
                return;
            }

            if (et == Ethernet.TYPE_IPV6) {
                boolean handled = handleNdpProxyIfNeeded(context, pkt, ethPkt);
                if (handled) {
                    return;
                }
            }

            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            if (dstMac.equals(VIRTUAL_MAC)) {
                log.info("vrouter");
                handleVRouter(context, pkt, ethPkt);
                return;
            }

            if (et != Ethernet.TYPE_IPV4 && et != Ethernet.TYPE_IPV6) {
                return;
            }

            handleLearningBridge(context, pkt, ethPkt);
        }
    }


    private void handleLearningBridge(PacketContext context, InboundPacket pkt, Ethernet ethPkt) {
        DeviceId recDevId = pkt.receivedFrom().deviceId();
        PortNumber recPort = pkt.receivedFrom().port();
        MacAddress srcMac = ethPkt.getSourceMAC();
        MacAddress dstMac = ethPkt.getDestinationMAC();

        bridgeTable.computeIfAbsent(recDevId, k -> new HashMap<>());

        if (bridgeTable.get(recDevId).get(srcMac) == null) {
            bridgeTable.get(recDevId).put(srcMac, recPort);
            log.info("Add an entry to the port table of `{}`. MAC address: `{}` => Port: `{}`.",
                    recDevId, srcMac, recPort);
        }

        PortNumber outPort = bridgeTable.get(recDevId).get(dstMac);
        if (outPort == null) {
            log.info("MAC address `{}` is missed on `{}`. Flood the packet.", dstMac, recDevId);
            flood(context);
        } else {
            log.info("MAC address `{}` is matched on `{}`. Install a flow rule.", dstMac, recDevId);
            installRule(context, recDevId, srcMac, dstMac, outPort);
        }
    }

    private void flood(PacketContext context) {
        packetOut(context, PortNumber.FLOOD);
    }

    private void packetOut(PacketContext context, PortNumber outPort) {
        context.treatmentBuilder().setOutput(outPort);
        context.send();
    }

    private void installRule(PacketContext context, DeviceId deviceId,
                             MacAddress srcMac, MacAddress dstMac, PortNumber outPort) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthSrc(srcMac)
                .matchEthDst(dstMac)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        FlowRule flowRule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(300)
                .fromApp(appId)
                .makeTemporary(300)
                .build();

        flowRuleService.applyFlowRules(flowRule);

        packetOut(context, outPort);
    }


    private void handleArpProxy(PacketContext context, InboundPacket pkt, Ethernet ethPkt) {
        ARP arp = (ARP) ethPkt.getPayload();
        short op = arp.getOpCode();

        Ip4Address senderIp = Ip4Address.valueOf(arp.getSenderProtocolAddress());
        MacAddress senderMac = MacAddress.valueOf(arp.getSenderHardwareAddress());
        Ip4Address targetIp = Ip4Address.valueOf(arp.getTargetProtocolAddress());

        DeviceId deviceId = pkt.receivedFrom().deviceId();
        PortNumber inPort = pkt.receivedFrom().port();

        if (dropExternalArpIfNeeded(deviceId, inPort, senderIp)) {
            context.block();
            return;
        }
        if (!senderIp.equals(SPEAKER_IP4) && !senderIp.equals(INNER_IP4) && !senderIp.equals(FRR_GATEWAY_IP4)) {
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

                Ethernet eth = ethPkt.duplicate();
                ARP arpReq = (ARP) eth.getPayload();

                if (eth.getSourceMAC().equals(FRR_MAC)) {
                    eth.setSourceMACAddress(VIRTUAL_MAC);
                    arpReq.setSenderHardwareAddress(VIRTUAL_MAC.toBytes());
                }

                edgefloodWithPacket(context, eth);
            }
        } else if (op == ARP.OP_REPLY) {
            Ethernet eth = ethPkt.duplicate();
            ARP arpReply = (ARP) eth.getPayload();

            MacAddress dstMac = MacAddress.valueOf(arpReply.getTargetHardwareAddress());
            if (dstMac.equals(VIRTUAL_MAC)) {
                eth.setDestinationMACAddress(FRR_MAC);
                arpReply.setTargetHardwareAddress(FRR_MAC.toBytes());
                dstMac = FRR_MAC;
            }

            MacLocation loc = macTable.get(dstMac);
            if (loc != null) {
                packetOutToWithPacket(context, loc.deviceId, loc.port, eth);
            } else {
                edgefloodWithPacket(context, eth);
            }
        }
    }

    private boolean handleNdpProxyIfNeeded(PacketContext context, InboundPacket pkt, Ethernet ethPkt) {
        IPv6 ipv6 = (IPv6) ethPkt.getPayload();

        IPacket payload = ipv6.getPayload();
        while (payload instanceof BaseOptions) {
            payload = payload.getPayload();
        }

        if (!(payload instanceof ICMP6)) {
            return false;
        }

        ICMP6 icmp6 = (ICMP6) payload;
        byte type = icmp6.getIcmpType();

        DeviceId deviceId = pkt.receivedFrom().deviceId();
        PortNumber inPort = pkt.receivedFrom().port();
        MacAddress srcMac = ethPkt.getSourceMAC();
        Ip6Address senderIp6 = Ip6Address.valueOf(ipv6.getSourceAddress());

        if (dropExternalNdpIfNeeded(deviceId, inPort, senderIp6)) {
            context.block();
            return true;
        }
        if (!senderIp6.equals(SPEAKER_IP6) && !senderIp6.equals(INNER_IP6) && !senderIp6.equals(FRR_GATEWAY_IP6)) {
            ndpTable.put(senderIp6, srcMac);
        }

        macTable.put(srcMac, new MacLocation(deviceId, inPort));

        if (type == ICMP6.NEIGHBOR_SOLICITATION) {
            NeighborSolicitation ns = (NeighborSolicitation) icmp6.getPayload();
            Ip6Address targetIp6 = Ip6Address.valueOf(ns.getTargetAddress());

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

                Ethernet eth = ethPkt.duplicate();
                if (eth.getSourceMAC().equals(FRR_MAC)) {
                    eth.setSourceMACAddress(VIRTUAL_MAC);
                }

                edgefloodWithPacket(context, eth);
            }
            return true;
        }

        if (type == ICMP6.NEIGHBOR_ADVERTISEMENT) {
            Ethernet eth = ethPkt.duplicate();

            if (eth.getDestinationMAC().equals(VIRTUAL_MAC)) {
                eth.setDestinationMACAddress(FRR_MAC);
            }

            MacLocation loc = macTable.get(eth.getDestinationMAC());
            if (loc != null) {
                packetOutToWithPacket(context, loc.deviceId, loc.port, eth);
            } else {
                edgefloodWithPacket(context, eth);
            }
            return true;
        }

        return false;
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
                             Ip6Address targetIp, MacAddress targetMac) {

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


    private static class MacLocation {
        DeviceId deviceId;
        PortNumber port;

        MacLocation(DeviceId deviceId, PortNumber port) {
            this.deviceId = deviceId;
            this.port = port;
        }
    }
    private void edgefloodWithPacket(PacketContext context, Ethernet eth) {
        Topology topo = topologyService.currentTopology();
        InboundPacket in = context.inPacket();

        for (Device device : deviceService.getDevices()) {
            for (Port port : deviceService.getPorts(device.id())) {
                if (!port.isEnabled()) {
                    continue;
                }

                ConnectPoint cp = new ConnectPoint(device.id(), port.number());
                if (topologyService.isInfrastructure(topo, cp)) {
                    continue;
                }
                if (cp.equals(in.receivedFrom())) {
                    continue;
                }
                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setOutput(port.number())
                        .build();

                packetService.emit(new DefaultOutboundPacket(
                        device.id(),
                        treatment,
                        ByteBuffer.wrap(eth.serialize())
                ));
            }
        }
    }

    private void packetOutToWithPacket(PacketContext context,
                                    DeviceId deviceId,
                                    PortNumber port,
                                    Ethernet eth) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(port)
                .build();

        packetService.emit(new DefaultOutboundPacket(
                deviceId,
                treatment,
                ByteBuffer.wrap(eth.serialize())
        ));
    }

    private void handleVRouter(PacketContext context,
                            InboundPacket pkt,
                            Ethernet ethPkt) {

        short et = ethPkt.getEtherType();

        if (et == Ethernet.TYPE_IPV4) {
            handleVRouterIpv4(context, pkt, ethPkt);
        } else if (et == Ethernet.TYPE_IPV6) {
            handleVRouterIpv6(context, pkt, ethPkt);
        }
    }

    private void handleVRouterIpv4(PacketContext context,
                                InboundPacket pkt,
                                Ethernet ethPkt) {

        IPv4 ipv4 = (IPv4) ethPkt.getPayload();
        Ip4Address dstIp = Ip4Address.valueOf(ipv4.getDestinationAddress());

        log.info("vRouter IPv4 packet dstIp={}", dstIp);

        if (dstIp.equals(Ip4Address.valueOf("192.168.70.14")) ||
        dstIp.equals(Ip4Address.valueOf("192.168.70.15"))) {
            log.info("drop duplicate icmp");
            return;
        }

        Route bestRoute = routeService.longestPrefixMatch(dstIp);

        if (bestRoute == null) {
            log.warn("No route found for {}", dstIp);
            return;
        }

        log.info("Matched route prefix={} nextHop={}",
                bestRoute.prefix(),
                bestRoute.nextHop());

        Ip4Address nextHopIp = bestRoute.nextHop().getIp4Address();

        MacAddress nextHopMac = arpTable.get(nextHopIp);
        if (nextHopMac == null) {
            log.warn("No MAC for nextHop {}", nextHopIp);
            return;
        }

        Ethernet outEth = ethPkt.duplicate();
        outEth.setSourceMACAddress(VIRTUAL_MAC);

        if (nextHopMac.equals(VIRTUAL_MAC)) {
            /* ---------- Case 1: 172.16.13.0/24 (FRR local domain) ---------- */
            if (bestRoute.prefix().toString().equals("172.16.13.0/24")) {

                MacAddress dstHostMac = arpTable.get(dstIp);
                if (dstHostMac == null) {
                    log.warn("No MAC for dst host {}", dstIp);
                    return;
                }

                MacLocation loc = macTable.get(dstHostMac);
                if (loc == null) {
                    log.warn("No location for dst host MAC {}", dstHostMac);
                    return;
                }

                outEth.setDestinationMACAddress(dstHostMac);

                log.info("vRouter direct-forward to local host {} via {}:{}",
                        dstIp, loc.deviceId, loc.port);

                packetOutToWithPacket(context, loc.deviceId, loc.port, outEth);
                return;
            }

            /* ---------- Case 2: 172.17.13.0/24 (R2 domain) ---------- */
            if (bestRoute.prefix().toString().equals("172.17.13.0/24")) {
                nextHopIp = Ip4Address.valueOf("192.168.63.2");
            }

            if (bestRoute.prefix().toString().equals("172.16.14.0/24")) {
                nextHopIp = Ip4Address.valueOf("192.168.70.14");
            }
            if (bestRoute.prefix().toString().equals("172.17.14.0/24")) {
                nextHopIp = Ip4Address.valueOf("192.168.70.253");
            }

            if (bestRoute.prefix().toString().equals("172.16.15.0/24")) {
                nextHopIp = Ip4Address.valueOf("192.168.70.15");
            }
            if (bestRoute.prefix().toString().equals("172.17.15.0/24")) {
                nextHopIp = Ip4Address.valueOf("192.168.70.253");
            }
        }

        nextHopMac = arpTable.get(nextHopIp);
        MacLocation loc = macTable.get(nextHopMac);
        if (loc == null) {
            log.warn("No location for nextHop MAC {}", nextHopMac);
            return;
        }

        outEth.setDestinationMACAddress(nextHopMac);

        log.info("Forward via {}:{} to nextHopMac={}",
                loc.deviceId, loc.port, nextHopMac);

        packetOutToWithPacket(context, loc.deviceId, loc.port, outEth);
    }

    private void handleVRouterIpv6(PacketContext context,
                                InboundPacket pkt,
                                Ethernet ethPkt) {

        IPv6 ipv6 = (IPv6) ethPkt.getPayload();
        Ip6Address dstIp = Ip6Address.valueOf(ipv6.getDestinationAddress());

        log.info("vRouter IPv6 packet dstIp={}", dstIp);

        if (dstIp.equals(Ip6Address.valueOf("fd70::14")) ||
        dstIp.equals(Ip6Address.valueOf("fd70::15"))) {
            log.info("drop duplicate icmp6");
            return;
        }

        Route bestRoute = routeService.longestPrefixMatch(dstIp);

        if (bestRoute == null) {
            log.warn("No v6 route found for {}", dstIp);
            return;
        }

        log.info("Matched v6 route prefix={} nextHop={}",
                bestRoute.prefix(), bestRoute.nextHop());

        Ip6Address nextHopIp;
        try {
            nextHopIp = bestRoute.nextHop().getIp6Address();
        } catch (Exception e) {
            log.warn("Route nextHop is not IPv6: {}", bestRoute.nextHop());
            return;
        }


        MacAddress nextHopMac = ndpTable.get(nextHopIp);
        if (nextHopMac == null) {
            log.warn("No MAC for v6 nextHop {} (NDP table miss)", nextHopIp);
            return;
        }

        Ethernet outEth = ethPkt.duplicate();
        outEth.setSourceMACAddress(VIRTUAL_MAC);

        if (nextHopMac.equals(VIRTUAL_MAC)) {

            // ---------- Case 1: 2a0b:4e07:c4:13::/64 (FRR local domain) ----------
            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:13::/64")) {

                MacAddress dstHostMac = ndpTable.get(dstIp);
                if (dstHostMac == null) {
                    log.warn("No MAC for dst host v6 {}", dstIp);
                    return;
                }

                MacLocation loc = macTable.get(dstHostMac);
                if (loc == null) {
                    log.warn("No location for dst host MAC {}", dstHostMac);
                    return;
                }

                outEth.setDestinationMACAddress(dstHostMac);

                log.info("vRouter v6 direct-forward to local host {} via {}:{}",
                        dstIp, loc.deviceId, loc.port);

                packetOutToWithPacket(context, loc.deviceId, loc.port, outEth);
                return;
            }

            // ---------- Case 2: 2a0b:4e07:c4:113::/64 (R2 domain) ----------
            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:113::/64")) {
                nextHopIp = Ip6Address.valueOf("fd63::2");
            }

            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:14::/64")) {
                nextHopIp = Ip6Address.valueOf("fd70::14");
            }
            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:114::/64")) {
                nextHopIp = Ip6Address.valueOf("fd70::fe");
            }

            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:15::/64")) {
                nextHopIp = Ip6Address.valueOf("fd70::15");
            }
            if (bestRoute.prefix().toString().equals("2a0b:4e07:c4:115::/64")) {
                nextHopIp = Ip6Address.valueOf("fd70::fe");
            }
        }

        nextHopMac = ndpTable.get(nextHopIp);
        MacLocation loc = macTable.get(nextHopMac);
        if (loc == null) {
            log.warn("No location for v6 nextHop MAC {}", nextHopMac);
            return;
        }

        outEth.setDestinationMACAddress(nextHopMac);

        log.info("vRouter v6 forward via {}:{} to nextHopMac={}",
                loc.deviceId, loc.port, nextHopMac);

        packetOutToWithPacket(context, loc.deviceId, loc.port, outEth);
    }


    private boolean dropExternalArpIfNeeded(
            DeviceId deviceId,
            PortNumber inPort,
            Ip4Address senderIp
    ) {
        if (deviceId.equals(ovs2) && inPort.equals(OVS2_TO_PEER14)) {
            if (!senderIp.equals(Ip4Address.valueOf("192.168.70.14"))) {
                log.warn("[ARP-GUARD][14] drop ARP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        if (deviceId.equals(ovs2) && inPort.equals(OVS2_TO_PEER15)) {
            if (!senderIp.equals(Ip4Address.valueOf("192.168.70.15"))) {
                log.warn("[ARP-GUARD][15] drop ARP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        if (deviceId.equals(ovs3) && inPort.equals(OVS3_TO_TA_PORT)) {
            if (!senderIp.equals(Ip4Address.valueOf("192.168.70.253"))) {
                log.warn("[ARP-GUARD][TA] drop ARP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        return false;
    }

    private boolean dropExternalNdpIfNeeded(
            DeviceId deviceId,
            PortNumber inPort,
            Ip6Address senderIp
    ) {
        if (deviceId.equals(ovs2) && inPort.equals(OVS2_TO_PEER14)) {
            if (!senderIp.equals(Ip6Address.valueOf("fd70::14"))) {
                log.warn("[NDP-GUARD][14] drop ARP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        if (deviceId.equals(ovs2) && inPort.equals(OVS2_TO_PEER15)) {
            if (!senderIp.equals(Ip6Address.valueOf("fd70::15"))) {
                log.warn("[NDP-GUARD][15] drop ARP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        if (deviceId.equals(ovs3) && inPort.equals(OVS3_TO_TA_PORT)) {
            if (!senderIp.equals(Ip6Address.valueOf("fd70::fe"))) {
                log.warn("[NDP-GUARD][TA] drop NDP from {} on {}:{}",
                        senderIp, deviceId, inPort);
                return true;
            }
        }

        return false;
    }

}
