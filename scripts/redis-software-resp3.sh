#!/usr/bin/env bash
# Checks that the latest Redis Software accepts RESP3 (Phase 0 step A5).
#
# Usage: scripts/redis-software-resp3.sh            (uses redislabs/redis:latest)
#        RS_IMAGE=redislabs/redis:<tag> scripts/redis-software-resp3.sh
#
# Starts a throwaway one-node Redis Software cluster in Docker on its own network, creates one database,
# runs scripts/check-resp3.sh against it, and removes the container and the network afterwards. Nothing is
# published on host ports. The admin and database passwords are random, used only inside this run, and
# never printed. Needs Docker with about 4 GB free for the node.
set -euo pipefail

image=${RS_IMAGE:-redislabs/redis:latest}
name="spark-redis-rs-resp3-$$"
network="$name-net"
admin="admin@example.com"
# openssl, not tr </dev/urandom | head: under pipefail, tr dies of SIGPIPE when head closes the pipe.
admin_pass=$(openssl rand -hex 16)
db_pass=$(openssl rand -hex 16)
here=$(cd "$(dirname "$0")" && pwd)

cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Runs a REST call inside the node, so the API never needs a published port.
api() { docker exec "$name" curl -sk -u "$admin:$admin_pass" -H 'Content-Type: application/json' "$@"; }
# Prints one top-level field of the JSON on stdin (empty if absent). Never prints the whole object, because
# a database object includes its password.
field() { python3 -c 'import json,sys
try: v = json.load(sys.stdin).get(sys.argv[1], "")
except Exception: v = ""
print(str(v).lower() if isinstance(v, bool) else v)' "$1"; }

echo "Starting $image as $name ..."
docker network create "$network" >/dev/null
docker run -d --name "$name" --hostname "$name" --network "$network" --cap-add sys_resource "$image" >/dev/null

echo "Waiting for the bootstrap API ..."
for _ in $(seq 1 90); do
  code=$(docker exec "$name" curl -sk -o /dev/null -w '%{http_code}' https://localhost:9443/v1/bootstrap 2>/dev/null || true)
  [ "$code" = "200" ] && break
  sleep 2
done
[ "$code" = "200" ] || { echo "Bootstrap API never came up (last HTTP $code)" >&2; exit 1; }

echo "Creating a one-node cluster ..."
docker exec "$name" rladmin cluster create name cluster.local username "$admin" password "$admin_pass" >/dev/null

echo "Waiting for the cluster API ..."
for _ in $(seq 1 60); do
  code=$(docker exec "$name" curl -sk -o /dev/null -w '%{http_code}' -u "$admin:$admin_pass" \
    https://localhost:9443/v1/cluster 2>/dev/null || true)
  [ "$code" = "200" ] && break
  sleep 2
done
[ "$code" = "200" ] || { echo "Cluster API never came up (last HTTP $code)" >&2; exit 1; }

rs_version=$(api https://localhost:9443/v1/nodes/1 | field software_version)
echo "Redis Software version: ${rs_version:-unknown}"

echo "Creating a database with default settings ..."
api -X POST https://localhost:9443/v1/bdbs \
  -d "{\"name\":\"resp3-check\",\"type\":\"redis\",\"memory_size\":104857600,\"port\":12000,\"authentication_redis_pass\":\"$db_pass\"}" \
  >/dev/null
for _ in $(seq 1 60); do
  status=$(api https://localhost:9443/v1/bdbs/1 | field status)
  [ "$status" = "active" ] && break
  sleep 2
done
if [ "$status" != "active" ]; then
  echo "Database never became active (last status: ${status:-none})" >&2
  docker exec "$name" rladmin status databases 2>&1 | sed 's/^/  /' >&2 || true
  exit 1
fi
db_info=$(api https://localhost:9443/v1/bdbs/1)
db_version=$(printf '%s' "$db_info" | field redis_version)
resp3_setting=$(printf '%s' "$db_info" | field resp3)
echo "Database: Redis ${db_version:-unknown}, resp3 setting: ${resp3_setting:-not reported}"

echo "Sending HELLO 3 ..."
"$here/check-resp3.sh" "redis://default:$db_pass@$name:12000" "$network"
