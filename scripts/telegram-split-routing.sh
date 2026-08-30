#!/bin/bash
# Split routing: Telegram API via WireGuard, webhook replies via public interface.
set -euo pipefail
MARK=0x42
TABLE=200
TG_CIDRS="149.154.160.0/20 91.108.4.0/22 91.108.8.0/22 91.108.12.0/22 91.108.16.0/22 91.108.20.0/22 91.108.56.0/22"
DOCKER_NET="172.18.0.0/16"

up() {
  grep -q "^${TABLE} telegram" /etc/iproute2/rt_tables || echo "${TABLE} telegram" >> /etc/iproute2/rt_tables

  ip route flush table ${TABLE} 2>/dev/null || true
  for cidr in $TG_CIDRS; do
    ip route add "$cidr" dev wg0 table ${TABLE} 2>/dev/null || true
    ip route del "$cidr" dev wg0 2>/dev/null || true
  done

  ip rule del fwmark ${MARK} 2>/dev/null || true
  ip rule add fwmark ${MARK} table ${TABLE} priority 100

  for cidr in $TG_CIDRS; do
    while iptables -t mangle -D OUTPUT -m conntrack --ctdir ORIGINAL -d "$cidr" -j MARK --set-mark ${MARK} 2>/dev/null; do :; done
    iptables -t mangle -A OUTPUT -m conntrack --ctdir ORIGINAL -d "$cidr" -j MARK --set-mark ${MARK}

    while iptables -t mangle -D PREROUTING -s ${DOCKER_NET} -d "$cidr" -j MARK --set-mark ${MARK} 2>/dev/null; do :; done
    iptables -t mangle -A PREROUTING -s ${DOCKER_NET} -d "$cidr" -j MARK --set-mark ${MARK}
  done

  while iptables -t nat -D POSTROUTING -o wg0 -j MASQUERADE 2>/dev/null; do :; done
  iptables -t nat -A POSTROUTING -o wg0 -j MASQUERADE

  sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null
  sysctl -w net.ipv4.conf.eth0.rp_filter=0 >/dev/null
  sysctl -w net.ipv4.conf.wg0.rp_filter=0 >/dev/null
}

down() {
  ip rule del fwmark ${MARK} 2>/dev/null || true
  ip route flush table ${TABLE} 2>/dev/null || true
  for cidr in $TG_CIDRS; do
    iptables -t mangle -D OUTPUT -m conntrack --ctdir ORIGINAL -d "$cidr" -j MARK --set-mark ${MARK} 2>/dev/null || true
    iptables -t mangle -D PREROUTING -s ${DOCKER_NET} -d "$cidr" -j MARK --set-mark ${MARK} 2>/dev/null || true
  done
  iptables -t nat -D POSTROUTING -o wg0 -j MASQUERADE 2>/dev/null || true
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  *) echo "usage: $0 {up|down}"; exit 1 ;;
esac
