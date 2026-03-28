#!/bin/bash
HOST=$1        # h1 or h2
IPV4=$2        # 172.16.13.2/24 / 172.16.13.3/24
GATEWAY4=$3    # 172.16.13.1
OVS=$4         # ovs1 or ovs2
TAG=$5         # h1 / h2
IPV6=$6        # e.g. 2a0b:4e07:c4:13::2/64
GATEWAY6=$7    # e.g. 2a0b:4e07:c4:13::1

sudo ip link add veth-${TAG} type veth peer name veth-${OVS}-${TAG} || true
sudo ip link set veth-${OVS}-${TAG} up
sudo ovs-vsctl add-port ${OVS} veth-${OVS}-${TAG} || true

HOST_PID=$(docker inspect -f '{{.State.Pid}}' $HOST)
sudo ln -sf /proc/$HOST_PID/ns/net /var/run/netns/$HOST_PID
sudo ip link set veth-${TAG} netns $HOST_PID

sudo nsenter -t $HOST_PID -n ip link set veth-${TAG} name eth0
sudo nsenter -t $HOST_PID -n ip link set eth0 up

# IPv4
sudo nsenter -t $HOST_PID -n ip addr add $IPV4 dev eth0
sudo nsenter -t $HOST_PID -n ip route add default via $GATEWAY4

# IPv6
sudo nsenter -t $HOST_PID -n ip -6 addr add $IPV6 dev eth0
sudo nsenter -t $HOST_PID -n ip -6 route add default via $GATEWAY6

