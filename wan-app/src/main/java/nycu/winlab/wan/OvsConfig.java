package nycu.winlab.wan;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.config.Config;

public class OvsConfig extends Config<ApplicationId> {

    public static final String OVS1 = "ovs1";
    public static final String OVS2 = "ovs2";
    public static final String OVS3 = "ovs3";

    @Override
    public boolean isValid() {
        return hasOnlyFields(OVS1, OVS2, OVS3);
    }

    public DeviceId ovs1() {
        return DeviceId.deviceId(get(OVS1, ""));
    }

    public DeviceId ovs2() {
        return DeviceId.deviceId(get(OVS2, ""));
    }

    public DeviceId ovs3() {
        return DeviceId.deviceId(get(OVS3, ""));
    }
}

