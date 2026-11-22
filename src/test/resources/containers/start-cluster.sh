#!/bin/sh
# Three-primary Redis Cluster on ports 7379-7381 inside one container, for the cluster suites.
# Replaces the Makefile's start-cluster target; the slot ranges are the same, so slot-dependent tests behave
# the same. Nodes announce 127.0.0.1 so clients on the host reach them through the fixed port mappings.
set -e
CONF_DIR=/usr/local/etc/redis
mkdir -p /data/cluster
for port in 7379 7380 7381; do
  redis-server --port "$port" --bind 0.0.0.0 --protected-mode no \
    --cluster-enabled yes --cluster-config-file "/data/cluster/nodes-$port.conf" \
    --cluster-announce-ip 127.0.0.1 \
    --aclfile "$CONF_DIR/users-cluster.acl" \
    --save "" --appendonly no \
    --dir /data/cluster --logfile "/data/cluster/redis-$port.log" --daemonize yes
done
for port in 7379 7380 7381; do
  until redis-cli -p "$port" ping >/dev/null 2>&1; do sleep 0.1; done
done
redis-cli -p 7380 cluster meet 127.0.0.1 7379 >/dev/null
redis-cli -p 7381 cluster meet 127.0.0.1 7379 >/dev/null
redis-cli -p 7379 cluster addslotsrange 0 2047 3334 5460 >/dev/null
redis-cli -p 7380 cluster addslotsrange 2048 3333 5461 7777 10000 10922 >/dev/null
redis-cli -p 7381 cluster addslotsrange 7778 9999 10923 16383 >/dev/null
for port in 7379 7380 7381; do
  until redis-cli -p "$port" cluster info | grep -q 'cluster_state:ok'; do sleep 0.2; done
done
echo "CLUSTER READY"
exec tail -F /data/cluster/redis-7379.log /data/cluster/redis-7380.log /data/cluster/redis-7381.log
