#!/bin/bash
FRR=$1

PID=$(docker inspect -f '{{.State.Pid}}' $FRR)

sudo mkdir -p /var/run/netns
sudo ln -sf /proc/$PID/ns/net /var/run/netns/$PID

sudo ip link add veth-frr type veth peer name veth-ovs1-frr || true
sudo ip link set veth-ovs1-frr up
sudo ovs-vsctl add-port ovs1 veth-ovs1-frr || true

sudo ip link set veth-frr netns $PID
sudo nsenter -t $PID -n ip link set veth-frr name eth1
sudo nsenter -t $PID -n ip link set eth1 up

sudo nsenter -t $PID -n ip addr add 172.16.13.69/24 dev eth1
sudo nsenter -t $PID -n ip -6 addr add 2a0b:4e07:c4:13::69/64 dev eth1

sudo nsenter -t $PID -n ip addr add 192.168.70.13/24 dev eth1
sudo nsenter -t $PID -n ip -6 addr add fd70::13/64 dev eth1

sudo nsenter -t $PID -n ip addr add 192.168.63.1/24 dev eth1
sudo nsenter -t $PID -n ip -6 addr add fd63::1/64 dev eth1
