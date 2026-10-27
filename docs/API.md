# The Kaos APIs

Kaos has two APIs besides its UI: the HTTP API of the server, which the
UI itself uses, and the Java API of the `kaos-core` library, which the
server embeds. Both handle more than the UI shows: several groups,
independent producers (pause, rate), topics, producers, groups and
consumers added and removed while running, and the `connection-reset`
fault.

## HTTP API

The server exposes the model of the Java API as a versioned, resource-oriented HTTP
API under `/api/v1`: REST to change the simulation, Server-Sent Events
to follow it, and [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
problem details for errors. A running server describes it as an
OpenAPI 3.1 document at
[`/api/v1/openapi.yaml`](http://localhost:8081/api/v1/openapi.yaml); the
source is
[`kaos-server/src/main/resources/openapi/kaos-v1.yaml`](../kaos-server/src/main/resources/openapi/kaos-v1.yaml).

Start a simulation with one topic, one producer and a group of three
consumers, one of which speaks the classic protocol:

```sh
curl -X POST localhost:8081/api/v1/simulator \
  -H 'Content-Type: application/json' \
  -d '{
        "topics": [{"name": "orders", "partitions": 12}],
        "producers": [{"topics": ["orders"]}],
        "groups": [{"id": "orders-app", "subscribedTopics": ["orders"],
                    "consumers": [{}, {}, {"overrides": {"protocol": "classic"}}]}]
      }'
```

Follow it: the stream sends the current `status` and `snapshot` on
connect, then each change.

```sh
curl -N localhost:8081/api/v1/simulator/events
```

Freeze a consumer, clear the fault, and stop the simulation:

```sh
curl -X PUT    localhost:8081/api/v1/simulator/groups/orders-app/consumers/consumer-0/faults/freeze
curl -X DELETE localhost:8081/api/v1/simulator/groups/orders-app/consumers/consumer-0/faults/freeze
curl -X DELETE localhost:8081/api/v1/simulator
```

`GET /api/v1/meta` returns the defaults, ranges, assignors and faults,
and `GET /api/v1/simulator/snapshot` the current snapshot.
[`kaos-server/src/test/scripts/curl-session.sh`](../kaos-server/src/test/scripts/curl-session.sh)
drives a whole session against a running server.

## Java API

`kaos-core` has a fluent API in the package `io.github.dajac.kaos`: build
a `Simulator`, add topics, producers and groups, `start()` it, and drive
the consumers through their handles. `close()` removes everything it
created on the cluster.

```java
try (var simulator = new Simulator("localhost:9092")) {
    Topic orders = simulator.addTopic("orders", 12);
    simulator.addProducer(p -> p.topics(orders).ratePerPartition(2));

    ConsumerGroup group = simulator.addGroup("orders-app", g -> g
        .subscribe(orders)
        .consumerDefaults(c -> c.protocol(Protocol.CONSUMER).assignor("uniform"))
        .consumers(3) // consumer-0..2 inherit the defaults
        .consumer(c -> c.protocol(Protocol.CLASSIC).assignor("range"))); // consumer-3 overrides them

    simulator.start(); // creates the topics, starts the proxy, the producer and the consumers

    // Wait until every consumer is a stable member of the group.
    group.awaitSnapshot(
        s -> s.consumers().stream().allMatch(c -> c.status() == ConsumerStatus.STABLE),
        Duration.ofMinutes(1));

    // Cut consumer-0 off the network: the proxy drops its requests until the fault is cleared.
    ConsumerHandle c0 = group.consumer(0);
    c0.inject(Fault.networkIsolation());
    group.awaitSnapshot(s -> s.consumers().get(0).partitions().isEmpty(), Duration.ofMinutes(2));
    c0.clear(Fault.networkIsolation());

    // Upgrade consumer-3 to the consumer protocol while the group runs.
    group.consumer(3).switchProtocol(Protocol.CONSUMER);

    GroupSnapshot snapshot = group.snapshot();
    System.out.println(snapshot.state() + " at epoch " + snapshot.epoch() + ", lag " + snapshot.lag());
} // close() stops everything and deletes the topics and the group
```

The example is compiled by the build:
[`JavaApiExampleTest`](../kaos-core/src/test/java/io/github/dajac/kaos/JavaApiExampleTest.java)
holds the same code and fails when the two differ, so a change of one
goes with a change of the other.

The other faults are `Fault.freeze()` (the consumer stops polling) and
`Fault.connectionReset()` (the proxy closes its connections). Consumers
also `leave()` and `join()` the group; producers `pause()` and
`resume()`; and listeners
(`withListener`, `addListener`) receive every new snapshot. Kaos is not
published to a Maven repository yet: `./mvnw -pl kaos-core -am install
-DskipTests -DskipITs` installs `io.github.dajac:kaos-core:0.1.0-SNAPSHOT`
in the local Maven repository.
