# Template-publish fan-out worker (Part 2)

The design is in [DESIGN.md](DESIGN.md) and the diagrams (call flow, item states, version graph) are in [DIAGRAMS.md](DIAGRAMS.md). This is the one component implemented: the worker that resolves the template reference of engagements through a slow downstream (~1 min per call, owned by another team).

## Run

```
cd fanout
export JAVA_HOME=<jdk 21>
./gradlew test
./gradlew bootRun --args='--fanout.demo.enabled=true --fanout.simulation.throttle-rate=0.05 --fanout.simulation.transient-failure-rate=0.1'
```

The demo seeds 300 engagements (70% in one big firm), publishes one event twice, and logs queue and worker counters until everything settles. The downstream is a simulated client with configurable latency and failure rates.

## Structure

Layered like our other Spring services, package by technical role under `com.caseware.fanout`:

| Package | Contents |
|---|---|
| `listener` | `PublishEventListener`: entry point for publish events (SQS consumer in production) |
| `service` | `PublishFanoutService` turns an event into work items. `FanoutWorkerService` leases, calls the slow downstream, records, retries |
| `repository` | `WorkQueueRepository` (queue contract and in-memory implementation), `TemplateIndexRepository`, `UnverifiedEngagementRepository` |
| `client` | `EngagementTemplateClient`: the slow downstream (~1 min), plus a simulated implementation |
| `resilience` | Adaptive concurrency limiter, circuit breaker, retry policy |
| `model`, `event`, `enums`, `exception` | Records, `PublishEvent`, `ItemStatus`, `DownstreamException` |
| `configuration` | `FanoutProperties` (all tuning in `application.yaml`), `FanoutConfiguration` (bean wiring), `DemoRunner` |

```
PublishEvent -> Listener -> PublishFanoutService -> WorkQueueRepository (1 item per engagement)
                                                          ^ lease / complete / retry
                                                   FanoutWorkerService -> EngagementTemplateClient (slow)
                                                          |
                                                          +-> TemplateIndexRepository (index)
```

The queue, index, directory and client are in-memory or simulated stand-ins, wired in `FanoutConfiguration`. Each repository and client interface documents what a real implementation must guarantee. The worker and the resilience classes are plain Java and do not depend on Spring.

## Key tradeoffs

**At-least-once plus idempotent effects, not exactly-once.** A crash between `TemplateIndexRepository.record` and `WorkQueueRepository.complete` re-runs the item. The index contract requires idempotency, so the second run is harmless. Exactly-once across two systems is not available, so I did not pretend.

**One item per engagement, coalesced.** The template reference of an engagement does not depend on which publish triggered the lookup. Two publishes in a row for the same template therefore share one 1-minute call instead of making two. Cost: no per-publish progress tracking in the worker. That belongs to the layer above.

**Concurrency budget is global and adaptive.** The downstream team has limited capacity, so in-flight calls are capped by one limiter shared by all jobs. It grows by one after a full window of successes and halves (at most once per cooldown) when the downstream throttles. The starting value should be the agreed number, for example 50. It is not a thread pool size. Virtual threads are cheap, and the limiter is the real bound.

**Fairness across firms.** The queue hands out items round-robin by firm. Without it, one publish on the 40,000-engagement firm would delay every other firm for hours.

**Leases with fencing, not locks.** A lease has a TTL and a token. A crashed worker's items come back after expiry. A slow worker that lost its lease gets `false` from `complete`, so it cannot overwrite a newer result. Cost: a slow-but-alive worker can cause a duplicate 1-minute call. There is no heartbeat, because the client timeout is required to be shorter than the TTL.

**Attempts are counted at lease time.** A worker that dies mid-call still burns an attempt, so a poison engagement that kills workers ends up `DEAD` instead of looping forever. Throttles refund the attempt, because being told to slow down is not the engagement's fault.

**Failure classes drive behaviour.**
- `THROTTLED`: shrink the budget, retry later with no attempt used.
- `TRANSIENT`: back off with full jitter, count the attempt, feed the breaker.
- `PERMANENT`: dead-letter at once. It counts as a healthy answer for the breaker.

**Circuit breaker pauses dispatch.** After N consecutive transient failures nothing is leased until the open period ends. Half-open lets calls through up to the limiter, not a single probe. That is simpler, and the limiter bounds the damage.

**Publish handling is stateless.** `PublishFanoutService` re-enumerates from the start on redelivery, and `enqueue` drops what already exists. A crash halfway through a 40,000-engagement listing is repaired by the broker redelivering the event, and no job state is stored.

## Known limits (deliberately not built)

- The in-memory queue is the only implementation, and it is a single JVM. Multi-worker correctness depends on the real store giving the same atomic lease semantics, for example a conditional write on the token.
- `DONE` items are never re-verified here. Re-verification is the reconciler's job.
- No metrics exporter. `FanoutWorkerService.stats()` and `WorkQueueRepository.stats()` expose the counters I would alarm on: dead items, throttles, fenced completions and pending age.
- No priority between publish-driven work and backfill. With a real queue I would add two priority classes so a backfill cannot delay a fresh publish.
- No per-region wiring: one worker instance per regional cell is assumed.

## Tests

22 tests, none touching the network or the real clock for logic that depends on time (a `TestClock` is injected):

- Store: coalescing, lease expiry and fencing, poison items, firm fairness, backoff and attempt refund.
- Limiter and breaker: bounds, once-per-cooldown decrease, half-open transitions, retry jitter bounds.
- Publish: paging, redelivery, crash mid-enumeration.
- Worker (real threads): concurrency never above the budget, retries then exactly one index write, permanent failure, exhausted retries, throttling, open breaker stops the hammering.
