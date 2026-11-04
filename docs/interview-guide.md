# Interview guide

Questions an SDE II interviewer is likely to ask about this project, with answers grounded in the code and in
measurements recorded in the repository. Numbers come from [benchmarking.md](benchmarking.md) and
[benchmarks/matching/README.md](benchmarks/matching/README.md); they were measured on a 2-vCPU development
sandbox and are not production capacity claims.

## Delivery semantics and correctness

**1. Walk me through what happens to a listing event.**
A producer posts it to the API, synchronously (applied in the request) or asynchronously (validated, then sent to
SQS). The worker long-polls the queue and calls the same `ListingIngestionService` the API uses. In one READ
COMMITTED transaction it checks the idempotency ledger, locks or creates the listing row, lets a pure decider
compare source versions, writes the listing, records the event in the ledger and, if the catalog changed, appends
a change event to the outbox. The worker deletes the SQS message only after commit. A relay publishes outbox rows
to a second queue, whose consumer re-runs matching for that listing.

**2. SQS is at-least-once. How do you avoid duplicate effects?**
I don't try to make delivery exactly-once; I make the effect idempotent. The ledger table is keyed by event id,
so a replay finds its row and returns the stored outcome without writing. A SHA-256 fingerprint of the event body
distinguishes a true replay from a producer reusing an id for different data, which is rejected rather than
ignored. Replaying 2,000 events in the benchmark produced 2,000 duplicates and no writes.

**3. Two copies of the same event arrive at the same instant on two threads. Walk through it.**
Both miss the ledger lookup. Both try to lock the listing row; one waits. The first commits its listing change and
ledger row. The second then inserts its ledger row with `ON CONFLICT DO NOTHING`, affects zero rows, throws, rolls
back its whole transaction and retries; the retry finds the committed ledger row and returns the stored outcome.
The primary key is the final arbiter, not the application. `ConcurrentIngestionIT` runs ten rounds of eight
simultaneous deliveries and asserts one effect per round; it also reports how many deliveries actually took that
arbiter path (44 of 80 in the recorded run), which shows the test exercises the race instead of passing by
accident.

**4. How do you handle out-of-order events and deletes?**
Every event carries the source's version. A newer version updates; an older one is recorded as
`STALE_IGNORED`; the same version with different content is a `VERSION_CONFLICT`, because silently picking one
would hide a producer bug. A withdrawal leaves a tombstone row that keeps its version, so a late, older upsert
cannot resurrect the listing; only a newer upsert reactivates it. Ordering by arrival time would be wrong under
SQS reordering and retries. The rules live in a pure function with a full decision-table test.

**5. Why the transactional outbox?**
Writing the listing and then publishing to SQS is a dual write: a crash in between loses the event; publishing
first can announce a change that rolls back. The outbox row commits atomically with the change. The relay claims
rows with `FOR UPDATE SKIP LOCKED`, so several relays never publish one row in overlapping runs, sends them, and
marks them published in the same transaction. If the commit fails after SQS accepted the batch, the rows are sent
again, which is fine because the consumer is idempotent. The trade-off I accepted is holding row locks during the
SQS call, bounded by small batches and client timeouts; lease columns or CDC would avoid it at a cost.

## Failure handling

**6. How do retries work, and why not retry forever?**
Transient failures (database, connection, unexpected exceptions) re-schedule the message by changing its
visibility timeout with exponential backoff and full jitter, 2 s to 5 min, so a burst of failures does not
retry in lockstep. After five receives the consumer copies the message to the DLQ with a `failureReason`,
`originalMessageId` and `receiveCount`, then deletes it. Permanent failures (malformed JSON, invalid event,
unknown source, reused id) go to the DLQ immediately; retrying them only delays the messages behind them. The
queue's redrive policy at six receives is the safety net for a worker that crashes before it can dead-letter.

**7. What happens when the database goes down?**
Without care, every message would burn its receive count and land in the DLQ, turning an outage into a manual
replay job. The consumer counts consecutive outage-type failures; after five it stops polling for 15 s and
resumes only when a database probe passes. Liveness does not depend on the database, so nothing restarts; the
worker's readiness does. `AsyncPipelineIT` covers an outage: messages are retried, not dead-lettered, and apply
once the database is back.

**8. How would you debug "my listing never showed up"?**
Start from the `X-Correlation-Id` the client got back. The API logs it; it travels as an SQS message attribute;
the worker puts it in the MDC with the message and event ids. Then the ledger: `processed_events` has a row per
event with its outcome and reason (`STALE_IGNORED` and `VERSION_CONFLICT` explain most surprises). No row means
it is still queued (check depth and age), in the DLQ (check `failureReason` with the admin peek endpoint), or was
never accepted (the API's 400 lists every invalid field).

## Data and performance

**9. What did you measure, and what limits throughput?**
The ingestion engine ran a median of 460 events/s on one thread and 828 events/s on eight (nine runs, 536–898) on
a 2-vCPU machine, with PostgreSQL on the same host. At eight threads both cores are saturated: 1.0–1.1 cores in
the JVM and 0.8–0.9 in PostgreSQL, which is why scaling from one to eight threads gave only about 1.8 times the
median throughput and the p50 rose from 2–3 ms to about 8 ms. The next lever is hardware: more cores or the database on its
own host. I also learned the host is noisy: identical runs differed by up to 65 %, so I report ranges.

**10. Matching is slower. Where does the time go?**
About 31 listings/s single-threaded, and the database does about 85 % of the CPU work. I profiled with
`pg_stat_statements`: the title-trigram candidate query is 84 % of all statement time at 32 ms per call, while the
GTIN and brand-plus-model lookups take 0.02–0.04 ms. The GIN trigram index makes that query 8–12 times faster than
a sequential scan, but synthetic titles share category words, so hundreds of rows clear the default similarity
threshold and must be rechecked and sorted. The next step is reducing those rows (a higher threshold, word
similarity, a category predicate), each gated on keeping blocking recall at 100 % on the labeled sets.

**11. Tell me about a time the data contradicted your hypothesis.**
`EXPLAIN ANALYZE` showed that with the default `random_page_cost = 4` the planner abandoned the GIN index for broad
titles: 62 of 300 sampled queries used sequential scans; at 1.1 none did, and mean latency fell from 57 to 24 ms.
I expected matching to speed up accordingly. An end-to-end A/B said otherwise: 646 s against 638 s, no change. The
application sends the query as a prepared statement, and after five executions PostgreSQL switches to a generic
plan that estimates 200 rows and always uses the index; I reproduced that with `PREPARE`/`EXECUTE`. I kept the
setting because it is the right cost model for cached data, documented that it does not speed up matching, and
profiled to find the real bottleneck instead.

**12. Which database design choices matter most?**
Constraints as invariants instead of application checks: a partial unique index allows one active reconciliation
per marketplace; another allows one live gap per product and marketplace, which is the deduplication rule and
makes dismissals suppress re-detection. Partial indexes keep queue-like scans small (only `PENDING` outbox rows,
only `PENDING` review candidates). A BRIN index serves time windows on the append-only ledger. Keyset pagination
keeps deep pages cheap and stable under inserts; a test inserts rows mid-pagination and checks nothing is
repeated or skipped. Triggers make the audit table append-only, even for SQL that bypasses the application.

## Matching and gaps

**13. Why rules instead of machine learning?**
No realistic labeled data, and reviewers need to see why a pair matched. The matcher retrieves candidates through
three indexes, scores five interpretable features, then applies explicit precedence rules for conflicting GTINs,
brands, models and variant attributes, storing every score and evidence code. I optimised for precision because
a false merge hides a real gap and corrupts every count downstream, while a missed match only adds review work.
On the hand-labeled sets `rules-v4` auto-matches with precision 1.0 at recall about 0.45; with the review queue,
F1 is 0.83 against 0.35 for a title-only baseline. I'd consider a learned model once reviewers have produced
enough labels, with the rules as features and guardrails.

**14. How do you know those accuracy numbers mean anything?**
They are honest about their limits. The development set was used to write the rules; the holdout set was labeled
before it was used, but one holdout error later motivated a rule, so later holdout numbers are optimistic.
Synthetic sets come from my own generator, so they test robustness to its perturbations, not real-world accuracy.
Each result in the repository states its dataset's bias. A test enforces floors so regressions fail the build.
Error analysis on the synthetic set found real bugs: voltage and ounce variants (20V vs 12V, 18 oz vs 24 oz) were
being auto-matched, which `rules-v4` fixed, raising synthetic auto-match precision from 0.920 to 0.982 at unchanged
recall.

**15. How is a gap's confidence decided, and how good is gap detection?**
By the closest candidate in the marketplace: none at all is 0.95; a sibling variant is 0.90; a candidate the
matcher judged non-matching gives 0.95 − 0.5 × its score. If the closest candidate is still in match review, the
confidence stays below 0.5, because on the development set only about 58 % of review-band pairs were true matches.
Priority combines confidence, reference coverage and a demand index that is explicitly synthetic. On the golden
catalog every true gap is surfaced (19 of 19); gaps with confidence of at least 0.5 have precision 0.78 and recall
0.74. It's 80 products, so it's a regression check, not an accuracy estimate.

**15a. Tell me about a correctness bug you found in your own system.**
A review pass asked whether match evidence could outlive the content it judged, so I wrote the scenarios as a
PostgreSQL integration test before touching the code: a listing that changes brand and model, a withdrawal, a
human approval followed by a content change, redelivered events. Five of six failed. Links were justified by
candidates scored on old content, a merge pass could rejoin separated products, gap evidence and the review API
accepted stale pairs. The fix is one rule in one place: a candidate counts only while both listings are active and
both content hashes equal the hashes it was scored on. Re-matching supersedes everything else; a superseded human
decision gets an audit row instead of disappearing; and when a listing leaves a product, the product is regrouped
into connected components of the remaining current evidence, so members that were only held together by the
changed listing split apart. Later, running the smoke scenario with the marketplace's events handled first showed
an order dependence: the merge kept the other product id, and a reviewer's dismissal stopped suppressing the gap.
Gaps now follow their product through merges, and when both products already had gaps the conflict is recorded,
never resolved by copying a decision between gaps (ADR 0013, ADR 0014).

**15b. How do reconciliation jobs survive restarts and crashes?**
They are rows, not tasks in an executor. A request inserts a `PENDING` job; pollers in any API instance claim one
with `FOR UPDATE SKIP LOCKED` and take a lease in database time, renewed after every batch. If the process dies,
the lease expires and another poller claims the job as its next attempt; a runner that paused past its lease
cannot overwrite the new owner's result, because every status write is conditional on `lease_owner`. Failures
retry with exponential backoff up to three attempts, permanent errors fail immediately, and both are visible on the
job. Tests cover restart, crash, saturation and two pollers competing for six jobs.

## Batch, security, operations

**16. How is the batch import restartable without duplicates?**
The job key is the source plus a hash of the canonical manifest, so resubmitting resumes the same job; a failed
job restarts as the next attempt. Event ids are UUIDv5 values derived from the job key, source, external id,
version and type, so a re-run republishes identical ids and the ledger absorbs them. Publishing is partitioned by
a hash of the event id with a completion marker per partition, so a restart skips finished partitions. Progress
comes from counting ledger rows for the job, not from a counter on the job row, which would be a hot row. A test
crashes the job mid-publish and checks that the resumed run loses nothing.

**17. How is the API secured, and what did you get wrong at first?**
API keys with roles, configured only as SHA-256 hashes and compared in constant time; the principal is a key
fingerprint, which is what the audit trail records, so clients can't spoof the reviewer. Operator endpoints need
the admin role and are also restricted to admin networks at the load balancer, which also never forwards
actuator paths. Reviewing the docs against the Terraform, I found that the in-task metrics collector scraped
`/actuator/prometheus` without a key, so with security on every scrape would have been rejected and the API's
metrics would never have reached CloudWatch. I exempted that one read-only endpoint, explained why it stays
private, and added an integration test for the whole authorization matrix.

**18. What would you do next?**
First, compile and run everything against the real libraries and Docker (`mvn verify`, the compose smoke test,
the failure drills): the environment I built this in could not reach Maven Central or Docker Hub, so the Spring
Boot apps were only type-checked against stubs and the stack never ran, and I say so everywhere. Then: reduce
trigram retrieval cost under the recall gate; run the reconciliation poller outside the API process; partition the
ledger by time with an archival policy; put SSO in front of the dashboard; and replace hand-tuned matching weights
with a model trained on reviewer decisions.
