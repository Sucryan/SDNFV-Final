package nycu.winlab.wan;

import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.MacAddress;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.net.device.DeviceService;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigEvent;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;


@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger("WanCp");

    private DeviceId ovs1;
    private DeviceId ovs2;
    private DeviceId ovs3; //private DeviceId ovs3;

    private static final PortNumber OVS1_TO_OVS2 = PortNumber.portNumber(1);
    private static final PortNumber OVS1_TO_FRR  = PortNumber.portNumber(2);

    private static final PortNumber OVS2_TO_OVS1 = PortNumber.portNumber(1);
    private static final PortNumber OVS2_TO_OVS3 = PortNumber.portNumber(3);

    private static final PortNumber OVS3_TO_OVS2 = PortNumber.portNumber(1);
    private static final PortNumber OVS3_TO_TA   = PortNumber.portNumber(3);

    private static final PortNumber R2_TO_OVS1   = PortNumber.portNumber(4);

    private static final PortNumber OVS2_TO_PEER14  = PortNumber.portNumber(4);
    private static final PortNumber OVS2_TO_PEER15  = PortNumber.portNumber(5);


    /* ====== Speaker IP ====== */
    private static final Ip4Address SPEAKER_IP4 = Ip4Address.valueOf("192.168.70.13");
    private static final Ip6Address SPEAKER_IP6 = Ip6Address.valueOf("fd70::13");
    private static final Ip4Address INNER_IP4 = Ip4Address.valueOf("192.168.63.1");
    private static final Ip6Address INNER_IP6 = Ip6Address.valueOf("fd63::1");
    private static final Ip4Address FRR_GATEWAY_IP4 = Ip4Address.valueOf("172.16.13.69");
    private static final Ip6Address FRR_GATEWAY_IP6 = Ip6Address.valueOf("2a0b:4e07:c4:13::69");

    private static final Ip4Address TA_ROUTER_IP4 = Ip4Address.valueOf("192.168.70.253");
    private static final Ip6Address TA_ROUTER_IP6 = Ip6Address.valueOf("fd70::fe");

    private static final Ip4Address PEER14_ROUTER_IP4 = Ip4Address.valueOf("192.168.70.14");
    private static final Ip6Address PEER14_ROUTER_IP6 = Ip6Address.valueOf("fd70::14");

    private static final Ip4Address PEER15_ROUTER_IP4 = Ip4Address.valueOf("192.168.70.15");
    private static final Ip6Address PEER15_ROUTER_IP6 = Ip6Address.valueOf("fd70::15");

    private static final MacAddress FRR_MAC = MacAddress.valueOf("46:bb:86:18:2c:47");
    private static final MacAddress VIRTUAL_MAC = MacAddress.valueOf("02:aa:bb:cc:dd:13");

    private static final int FLOW_PRIORITY = 50000;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfgService;

    private ApplicationId appId;

    private final ConfigFactory<ApplicationId, OvsConfig> ovsConfigFactory =
    new ConfigFactory<ApplicationId, OvsConfig>(
            APP_SUBJECT_FACTORY,
            OvsConfig.class,
            "wan-ovs") {

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

                ovs1 = cfg.ovs1();
                ovs2 = cfg.ovs2();
                ovs3 = cfg.ovs3();
                log.info("OVS config received: ovs1={}, ovs2={}, ovs3={}", ovs1, ovs2, ovs3);

                installSpeakerV4MacRewrite();
                installSpeakerV6MacRewrite();
            }
        }
    }


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.wan");

        netcfgService.registerConfigFactory(ovsConfigFactory);
        netcfgService.addListener(cfgListener);

        OvsConfig cfg = netcfgService.getConfig(appId, OvsConfig.class);
        if (cfg != null) {
            ovs1 = cfg.ovs1();
            ovs2 = cfg.ovs2();
            ovs3 = cfg.ovs3();
            log.info("OVS config received: ovs1={}, ovs2={}, ovs3={}", ovs1, ovs2, ovs3);

            installSpeakerV4MacRewrite();
            installSpeakerV6MacRewrite();
        } else {
            log.info("WAN app started, waiting for OVS config...");
        }
    }

    @Deactivate
    protected void deactivate() {
        netcfgService.removeListener(cfgListener);
        netcfgService.unregisterConfigFactory(ovsConfigFactory);
        flowRuleService.removeFlowRulesById(appId);
        log.info("WAN app stopped");
    }

    /* ================= IPv4: dst/src = speaker ================= */
    private void installSpeakerV4MacRewrite() {
        TrafficSelector v4ToSpeakerOnOvs3 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS3_TO_TA)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(TA_ROUTER_IP4.toIpPrefix())
                        .matchIPDst(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS3)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(TA_ROUTER_IP4.toIpPrefix())
                        .matchIPDst(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4Peer14ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_PEER14)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(PEER14_ROUTER_IP4.toIpPrefix())
                        .matchIPDst(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4Peer15ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_PEER15)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(PEER15_ROUTER_IP4.toIpPrefix())
                        .matchIPDst(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4ToSpeakerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_OVS2)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4FromFrrGatewayOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(FRR_GATEWAY_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4FromSpeakerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(SPEAKER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4ToTAOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(SPEAKER_IP4.toIpPrefix())
                        .matchIPDst(TA_ROUTER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4ToPeer14OnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(SPEAKER_IP4.toIpPrefix())
                        .matchIPDst(PEER14_ROUTER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4ToPeer15OnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(SPEAKER_IP4.toIpPrefix())
                        .matchIPDst(PEER15_ROUTER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4FromSpeakerOnOvs3 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS3_TO_OVS2)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(SPEAKER_IP4.toIpPrefix())
                        .matchIPDst(TA_ROUTER_IP4.toIpPrefix())
                        .build();

        // Forward path
        addFlow(ovs3, v4ToSpeakerOnOvs3, OVS3_TO_OVS2, null, null);
        addFlow(ovs2, v4ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs2, v4Peer14ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs2, v4Peer15ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs1, v4ToSpeakerOnOvs1, OVS1_TO_FRR, FRR_MAC, null);

        // Reverse path
        addFlow(ovs1, v4FromSpeakerOnOvs1, OVS1_TO_OVS2, null, VIRTUAL_MAC);
        addFlow(ovs2, v4ToTAOnOvs2, OVS2_TO_OVS3, null, null);
        addFlow(ovs2, v4ToPeer14OnOvs2, OVS2_TO_PEER14, null, null);
        addFlow(ovs2, v4ToPeer15OnOvs2, OVS2_TO_PEER15, null, null);
        addFlow(ovs3, v4FromSpeakerOnOvs3, OVS3_TO_TA, null, null);


        TrafficSelector v4ToInnerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(R2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(INNER_IP4.toIpPrefix())
                        .build();

        TrafficSelector v4FromInnerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(INNER_IP4.toIpPrefix())
                        .build();

        // Forward path
        addFlow(ovs1, v4ToInnerOnOvs1, OVS1_TO_FRR, FRR_MAC, null);

        // Reverse path
        addFlow(ovs1, v4FromInnerOnOvs1, R2_TO_OVS1, null, VIRTUAL_MAC);

        // Forward path
        //addFlow(ovs1, v4FromFrrGatewayOnOvs1, OVS1_TO_OVS2, null, VIRTUAL_MAC);

    }

    /* ================= IPv6: dst/src = speaker ================= */
    private void installSpeakerV6MacRewrite() {
        TrafficSelector v6ToSpeakerOnOvs3 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS3_TO_TA)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(TA_ROUTER_IP6.toIpPrefix())
                        .matchIPv6Dst(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS3)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(TA_ROUTER_IP6.toIpPrefix())
                        .matchIPv6Dst(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6Peer14ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_PEER14)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(PEER14_ROUTER_IP6.toIpPrefix())
                        .matchIPv6Dst(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6Peer15ToSpeakerOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_PEER15)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(PEER15_ROUTER_IP6.toIpPrefix())
                        .matchIPv6Dst(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6ToSpeakerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_OVS2)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Dst(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6FromSpeakerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(SPEAKER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6FromFrrGatewayOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(FRR_GATEWAY_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6ToTAOnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(SPEAKER_IP6.toIpPrefix())
                        .matchIPv6Dst(TA_ROUTER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6ToPeer14OnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(SPEAKER_IP6.toIpPrefix())
                        .matchIPv6Dst(PEER14_ROUTER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6ToPeer15OnOvs2 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(SPEAKER_IP6.toIpPrefix())
                        .matchIPv6Dst(PEER15_ROUTER_IP6.toIpPrefix())
                        .build();


        TrafficSelector v6FromSpeakerOnOvs3 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS3_TO_OVS2)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(SPEAKER_IP6.toIpPrefix())
                        .matchIPv6Dst(TA_ROUTER_IP6.toIpPrefix())
                        .build();

        // Forward path
        addFlow(ovs3, v6ToSpeakerOnOvs3, OVS3_TO_OVS2, null, null);
        addFlow(ovs2, v6ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs2, v6Peer14ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs2, v6Peer15ToSpeakerOnOvs2, OVS2_TO_OVS1, null, null);
        addFlow(ovs1, v6ToSpeakerOnOvs1, OVS1_TO_FRR, FRR_MAC, null);

        // Reverse path
        addFlow(ovs1, v6FromSpeakerOnOvs1, OVS1_TO_OVS2, null, VIRTUAL_MAC);
        addFlow(ovs2, v6ToTAOnOvs2, OVS2_TO_OVS3, null, null);
        addFlow(ovs2, v6ToPeer14OnOvs2, OVS2_TO_PEER14, null, null);
        addFlow(ovs2, v6ToPeer15OnOvs2, OVS2_TO_PEER15, null, null);
        addFlow(ovs3, v6FromSpeakerOnOvs3, OVS3_TO_TA, null, null);


        TrafficSelector v6ToInnerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(R2_TO_OVS1)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Dst(INNER_IP6.toIpPrefix())
                        .build();

        TrafficSelector v6FromInnerOnOvs1 =
                DefaultTrafficSelector.builder()
                        .matchInPort(OVS1_TO_FRR)
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(INNER_IP6.toIpPrefix())
                        .build();

        // Forward path
        addFlow(ovs1, v6ToInnerOnOvs1, OVS1_TO_FRR, FRR_MAC, null);

        // Reverse path
        addFlow(ovs1, v6FromInnerOnOvs1, R2_TO_OVS1, null, VIRTUAL_MAC);
        //addFlow(ovs1, v6FromFrrGatewayOnOvs1, OVS1_TO_OVS2, null, VIRTUAL_MAC);
    }

    private void addFlow(DeviceId device,
                     TrafficSelector selector,
                     PortNumber outPort,
                     MacAddress setDstMac,
                     MacAddress setSrcMac) {

        TrafficTreatment.Builder tb = DefaultTrafficTreatment.builder();

        if (setDstMac != null) {
            tb.setEthDst(setDstMac);
        }
        if (setSrcMac != null) {
            tb.setEthSrc(setSrcMac);
        }

        tb.setOutput(outPort);

        FlowRule rule = DefaultFlowRule.builder()
                .forDevice(device)
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(tb.build())
                .withPriority(FLOW_PRIORITY)
                .makePermanent()
                .build();

        flowRuleService.applyFlowRules(rule);
        log.info("Installed speaker rewrite flow on {}", device);
    }
}
