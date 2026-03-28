SHELL := /bin/bash

# ================================
#  Basic container names
# ================================
ONOS = onos
FRR1 = FRRouting
FRR2 = R2
H1 = h1
H2 = h2
H3 = h3

define ACTIVATE
curl -s -u onos:rocks -X POST \
 http://localhost:8181/onos/v1/applications/$(1)/active
endef

# ================================
#  deploy
# ================================
deploy:
	@echo "=== build ONOS ==="
	docker run -d --rm --name $(ONOS) -p 8181:8181 -p 6653:6653 -p 8101:8101 onosproject/onos:2.7-latest

	@echo "=== build Hosts ==="
	docker run -d --net none --privileged --cap-add NET_ADMIN --cap-add NET_BROADCAST --name $(H1) host
	docker run -d --net none --privileged --cap-add NET_ADMIN --cap-add NET_BROADCAST --name $(H2) host
	docker run -d --net none --privileged --cap-add NET_ADMIN --cap-add NET_BROADCAST --name $(H3) host

	@echo "=== build FRRouting Containers ==="
	docker run --privileged -d --name $(FRR1) frrouting/frr-debian
	docker run --privileged -d --name $(FRR2) frrouting/frr-debian

	@echo "=== set router ==="
	docker cp FRRouting.conf $(FRR1):/etc/frr/frr.conf
	docker cp daemons.txt $(FRR1):/etc/frr/daemons
	docker cp R2_frr.conf $(FRR2):/etc/frr/frr.conf
	docker cp daemons.txt $(FRR2):/etc/frr/daemons
	
	docker restart FRRouting
	docker restart R2
	
	@echo "=== start ONOS Apps ==="
	sleep 60

	#$(call ACTIVATE,org.onosproject.bgprouter)
	#$(call ACTIVATE,org.onosproject.fibinstaller)
	$(call ACTIVATE,org.onosproject.fpm)
	$(call ACTIVATE,org.onosproject.hostprovider)
	$(call ACTIVATE,org.onosproject.lldpprovider)
	$(call ACTIVATE,org.onosproject.openflow)
	$(call ACTIVATE,org.onosproject.openflow-base)
	$(call ACTIVATE,org.onosproject.optical-model)
	$(call ACTIVATE,org.onosproject.route-service)

	@echo "=== ONOS Apps 啟動完成 ==="
	

	@echo "=== 建立 OVS Bridges ==="
	sudo ovs-vsctl --if-exists del-br ovs1
	sudo ovs-vsctl --if-exists del-br ovs2
	sudo ovs-vsctl add-br ovs1 -- set bridge ovs1 protocols=OpenFlow14
	sudo ovs-vsctl add-br ovs2 -- set bridge ovs2 protocols=OpenFlow14
	sudo ovs-vsctl set-controller ovs1 tcp:127.0.0.1:6653
	sudo ovs-vsctl set-controller ovs2 tcp:127.0.0.1:6653

	@echo "=== 建立 OVS1 <-> OVS2 veth ==="
	sudo ip link add veth-o1-o2 type veth peer name veth-o2-o1 || true
	sudo ip link set veth-o1-o2 up
	sudo ip link set veth-o2-o1 up
	sudo ovs-vsctl add-port ovs1 veth-o1-o2
	sudo ovs-vsctl add-port ovs2 veth-o2-o1

	@echo "=== Connect FRRouting to OVS1 ==="
	$$(bash scripts/connect_frr.sh $(FRR1))
	
	@echo "=== Connect h2 to OVS1 ==="
	$$(bash scripts/connect_host.sh $(H2) 172.16.13.3/24 172.16.13.1 ovs1 h2 2a0b:4e07:c4:13::3/64 2a0b:4e07:c4:13::1)

	@echo "=== Connect h1 to OVS2 ==="
	$$(bash scripts/connect_host.sh $(H1) 172.16.13.2/24 172.16.13.1 ovs2 h1 2a0b:4e07:c4:13::2/64 2a0b:4e07:c4:13::1)

	@echo "=== Connect R2 & h3 ==="
	$$(bash scripts/connect_r2.sh $(FRR2) $(H3))

	@echo "=== 開啟 IP Forwarding ==="
	docker exec $(FRR1) sysctl -w net.ipv4.ip_forward=1
	docker exec $(FRR2) sysctl -w net.ipv4.ip_forward=1
	docker exec $(FRR1) sysctl -w net.ipv6.conf.all.forwarding=1
	docker exec $(FRR2) sysctl -w net.ipv6.conf.all.forwarding=1
	
	@echo "=== 啟動 WireGuard ==="
	sudo wg-quick up wg0 || true
	
	@echo "=== Add VXLAN port to OVS2 ==="
	sudo ovs-vsctl --may-exist add-port ovs2 vxlan-to-ta \
	-- set interface vxlan-to-ta type=vxlan \
	options:remote_ip=192.168.60.13 \
	mtu_request=1370
	
	sudo ovs-vsctl --may-exist add-port ovs2 vxlan-to-14 \
	-- set interface vxlan-to-14 type=vxlan \
	options:remote_ip=192.168.61.14 \
	mtu_request=1370
	
	sudo ovs-vsctl --may-exist add-port ovs2 vxlan-to-15 \
	-- set interface vxlan-to-15 type=vxlan \
	options:remote_ip=192.168.61.15 \
	mtu_request=1370

	#onos-netcfg localhost wan.json
	
	
	@echo "=== Install Wan App ==="
	@(cd wan-app && mvn clean install -DskipTests && onos-app localhost install! target/wan-app-1.0-SNAPSHOT.oar)
	@(cd ..)
	
	@echo "=== Install bridge App ==="
	@(cd bridge-app && mvn clean install -DskipTests && onos-app localhost install! target/bridge-app-1.0-SNAPSHOT.oar)
	
	sleep 10
	
	@echo "=== Generate OVS config ==="
	bash scripts/gen-ovs-config.sh
	onos-netcfg localhost ovs.json
	echo "OVS mapping:"
	echo "  ovs1 -> of:$OVS1_DPID"
	echo "  ovs2 -> of:$OVS2_DPID"
	
	
	#@echo "=== Install Proxy NDP App ==="
	#@(cd proxyndp && onos-app localhost install! target/proxyndp-1.0-SNAPSHOT.oar)
	#@(cd ..)
	
	#@echo "=== Install bridge App ==="
	#@(cd bridge-app && onos-app localhost install! target/bridge-app-1.0-SNAPSHOT.oar)
	
	

	@echo "=== complete ==="
	@(cd ..)

# ================================
#  clean
# ================================
clean:
	@echo "=== 刪除 Docker Containers ==="
	-docker rm -f $(ONOS) $(FRR1) $(FRR2) $(H1) $(H2) $(H3)

	@echo "=== 清除 OVS Bridges ==="
	sudo ovs-vsctl --if-exists del-br ovs1
	sudo ovs-vsctl --if-exists del-br ovs2
	
	@echo "=== 刪除 VXLAN Port ==="
	-sudo ovs-vsctl --if-exists del-port ovs2 vxlan-to-ta

	@echo "=== 刪除 veth Interfaces ==="
	-for i in veth-o1-o2 veth-o2-o1 veth-frr veth-ovs1-frr veth-h1 veth-ovs2-h1 veth-h2 veth-ovs1-h2 veth-r2-ovs1 veth-h3-r2 veth-r2-h3; do \
		sudo ip link del $$i 2>/dev/null || true; \
	done

	@echo "=== 清除 netns symlinks ==="
	sudo rm -f /var/run/netns/*

	@echo "=== 清除 wireguard ==="
	sudo wg-quick down wg0

	@echo "=== 清理完成 ==="

