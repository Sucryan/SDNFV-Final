#!/bin/bash
set -e

OVS1_DPID=$(sudo ovs-vsctl get bridge ovs1 datapath-id | tr -d '"')
OVS2_DPID=$(sudo ovs-vsctl get bridge ovs2 datapath-id | tr -d '"')

if [ -z "$OVS1_DPID" ] || [ -z "$OVS2_DPID" ]; then
  echo "Failed to get ovs datapath-id"
  exit 1
fi

cat > ovs.json <<EOF
{
  "apps": {
    "nycu.winlab.wan": {
      "wan-ovs": {
        "ovs1": "of:$OVS1_DPID",
        "ovs2": "of:$OVS2_DPID",
        "ovs3": "of:00002295d4fdc743"
      }
    },
    "nycu.winlab.bridge": {
      "bridge-ovs": {
        "ovs1": "of:$OVS1_DPID",
        "ovs2": "of:$OVS2_DPID",
        "ovs3": "of:00002295d4fdc743"
      }
    }
  }

}
EOF

echo "Generated ovs.json:"
cat ovs.json
