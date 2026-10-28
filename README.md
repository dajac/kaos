# Kaos

*A playground for Apache Kafka consumer groups: run them, break them,
and watch them rebalance.*

Kaos is an interactive simulator of Apache Kafka consumer groups, built
for the new consumer rebalance protocol of
[KIP-848](https://cwiki.apache.org/confluence/display/KAFKA/KIP-848%3A+The+Next+Generation+of+the+Consumer+Rebalance+Protocol).

It runs real Kafka consumers and producers against a cluster, lets you
disturb them, and shows live how the group rebalances: which partitions
each consumer owns, receives or gives up, and how the lag evolves. Use
it to see the new protocol at work, compare it with the classic one, or
watch what a migration from one to the other does to a running group.

![The Rows view: one row per consumer, one strip per topic, the lag of each partition as a gauge](docs/images/rows-view.png)

*A group of five consumers on two topics: `consumer-1` is cut off the
network, `consumer-3` is frozen and does not take the partitions (in
blue) that the coordinator moved to it, `consumer-4` speaks the classic
protocol.*

## Quickstart

You need [Docker](https://docs.docker.com/get-docker/). Step 1 uses
Docker Compose, which comes with Docker Desktop; without it, see
[Without Compose](#without-compose).

1. Start a Kafka broker and Kaos:

   ```sh
   curl -fsSL https://raw.githubusercontent.com/dajac/kaos/main/compose.yaml | docker compose -f - up
   ```

   This runs [`compose.yaml`](compose.yaml): a one-broker cluster and
   Kaos, on one Docker network.

2. Open <http://localhost:8081>. The broker takes a few seconds to
   come up, so wait a moment before the first start.

3. Press **Start**. Kaos creates a topic, starts a producer and a group
   of three consumers, and shows one row per consumer with the
   partitions it owns.

4. Disturb the group. From the **Actions** menu of a consumer, try
   **Partition network**, **Freeze consumer** or **Downgrade to
   classic**, and watch the group react.

5. Press **Stop** when you are done: Kaos deletes the topics and the
   group it created. Ctrl-C stops the containers, and
   `docker compose -p kaos down` removes them.

The panel on the left sets up the group before a start: its protocol
and assignor, its topics and partitions, the number of consumers, and
per-consumer settings.

### Without Compose

The same setup in three commands: a network, a broker that advertises
its name on that network, and Kaos.

```sh
docker network create kaos

docker run -d --name kafka --network kaos \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  apache/kafka:4.4.0

docker run --rm --network kaos -p 8081:8081 ghcr.io/dajac/kaos --bootstrap-server kafka:9092
```

Then follow steps 2 to 5 above. Ctrl-C stops Kaos, and
`docker rm -f kafka && docker network rm kaos` removes the rest.

## What you can do

- **Run a group of real consumers** on one or more topics, with the
  `consumer` protocol (KIP-848), the `classic` protocol, or a mix of
  both, and any assignor.
- **Inject faults** into a consumer: cut it off the network, freeze its
  poll loop, stop it and start it again.
- **Switch the protocol** of a consumer while the group runs, from
  `classic` to `consumer` and back.
- **Watch the coordinator's view**: the state and epoch of the group,
  the partitions each consumer owns or waits for, and the lag of every
  partition.

## Use your own Kafka cluster

Kaos works with any Kafka cluster from version 4.0, where the KIP-848
protocol is on by default (the assignment interval setting of a group
needs 4.3).

```sh
docker run --rm -p 8081:8081 ghcr.io/dajac/kaos --bootstrap-server <host>:<port>
```

Kaos connects to the bootstrap address, then to each broker at the
address the broker *advertises*. The container must reach those
addresses too:

- **A broker in Docker**: run Kaos on the network of the broker, and
  have the broker advertise an address that resolves on that network,
  as `compose.yaml` does:
  `docker run --rm --network <network> -p 8081:8081 ghcr.io/dajac/kaos --bootstrap-server <broker>:9092`.
- **A broker on your machine** that advertises `localhost:9092`: inside
  a container, `localhost` is the container itself. On Linux, add
  `--network host` (then `-p` is not needed). Elsewhere, run the jar
  instead (see below).

Kaos connects in plaintext, without authentication.

**A given version.** `ghcr.io/dajac/kaos` is the latest release; add
the version to pin one, as in `ghcr.io/dajac/kaos:0.1.0`. With Compose,
set `KAOS_VERSION=0.1.0` before `docker compose`.

**Without Docker.** Download `kaos-server-<version>.jar` from the
[latest release](https://github.com/dajac/kaos/releases/latest) and run
it with Java 21:

```sh
java -jar kaos-server-<version>.jar --bootstrap-server localhost:9092
```

**Nightly builds.** `ghcr.io/dajac/kaos:nightly` is built from `main`
every night, for trying what is not released yet.

### Options

| Option | Default | Meaning |
|---|---|---|
| `--bootstrap-server` | `localhost:9092` | The Kafka cluster to connect to |
| `--port` | `8081` | The port of the UI and the HTTP API |
| `--proxy-port` | `19092` | The first port of the internal proxy that applies the network faults |

With Docker, the options follow the image name, and the UI moves to
another port with the `-p` mapping (`-p 9000:8081`) rather than
`--port`. `--help` lists the options and `--version` prints the
version.

## Good to know

- **Kaos owns its topics and its groups.** Stop deletes the topics and
  the groups it created, and so does stopping Kaos itself. A start
  fails when one of its topics already exists, for example after Kaos
  was killed: delete the topic, or change the group id, since the UI
  names the topics after it.
- **Faults follow the Kafka timeouts.** A consumer cut off the network
  loses its partitions after the session timeout (45 s by default), a
  frozen one after `max.poll.interval.ms` (5 min by default). Once the
  network is back, a consumer recovers within about 10 s.
- **The lag counts from offset 0 until the first commit**, so a gauge
  can fill right after a start and drop at the first commit.
- **Eager classic assignors** (all but `cooperative-sticky`) give up
  all their partitions during a rebalance, so the row of such a
  consumer is empty until the rebalance completes.
- **The broker bounds the group settings.** With its defaults, it
  refuses a session timeout below 45 s or a heartbeat interval below
  5 s, and the start fails with the broker's message.
- **A start against an unreachable cluster** fails after about 30 s,
  with the reason in the UI.

## Automate it

Everything the UI does goes through a versioned HTTP API, so scripts
can drive Kaos too, and the simulator is also a Java library. See
[docs/API.md](docs/API.md).

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) explains how to build Kaos from
source and run the tests.

## License

Kaos is licensed under the [Apache License, Version 2.0](LICENSE).

## Trademarks

Apache Kafka, Kafka, and Apache are trademarks of The Apache Software
Foundation. Kaos is not affiliated with or endorsed by The Apache
Software Foundation.
