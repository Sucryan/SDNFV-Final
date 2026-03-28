#!/bin/bash
R2=$1
H3=$2

R2PID=$(docker inspect -f '{{.State.Pid}}' $R2)
H3PID=$(docker inspect -f '{{.State.Pid}}' $H3)

sudo mkdir -p /var/run/netns
sudo ln -sf /proc/$R2PID/ns/net /var/run/netns/$R2PID
sudo ln -sf /proc/$H3PID/ns/net /var/run/netns/$H3PID

sudo ip link add veth-r2-ovs1 type veth peer name veth-ovs1-r2 || true
sudo ip link add veth-h3-r2 type veth peer name veth-r2-h3 || true

sudo ip link set veth-ovs1-r2 up
sudo ovs-vsctl add-port ovs1 veth-ovs1-r2 || true
sudo ip link set veth-r2-ovs1 netns $R2PID
sudo nsenter -t $R2PID -n ip link set veth-r2-ovs1 name eth1
sudo nsenter -t $R2PID -n ip link set eth1 up
sudo nsenter -t $R2PID -n ip addr add 192.168.63.2/24 dev eth1
sudo nsenter -t $R2PID -n ip -6 addr add fd63::2/64 dev eth1

sudo ip link set veth-h3-r2 netns $H3PID
sudo nsenter -t $H3PID -n ip link set veth-h3-r2 name eth0
sudo nsenter -t $H3PID -n ip link set eth0 up
sudo nsenter -t $H3PID -n ip addr add 172.17.13.2/24 dev eth0
sudo nsenter -t $H3PID -n ip -6 addr add 2a0b:4e07:c4:113::2/64 dev eth0
sudo nsenter -t $H3PID -n ip route add default via 172.17.13.1
sudo nsenter -t $H3PID -n ip -6 route add default via 2a0b:4e07:c4:113::1

sudo ip link set veth-r2-h3 netns $R2PID
sudo nsenter -t $R2PID -n ip link set veth-r2-h3 name eth2
sudo nsenter -t $R2PID -n ip link set eth2 up
sudo nsenter -t $R2PID -n ip addr add 172.17.13.1/24 dev eth2
sudo nsenter -t $R2PID -n ip -6 addr add 2a0b:4e07:c4:113::1/64 dev eth2
