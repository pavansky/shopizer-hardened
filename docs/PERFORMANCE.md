# Performance: measured, then explained

All numbers come from the demo stack in `deploy/` on one laptop (Apple Silicon, Docker Desktop, native arm64 images). The load script is `deploy/loadtest/k6-shop.js`: 20 users browsing and 4 users checking out, for 60 seconds.

## 1. Results

| Measure | Result |
|---|---|
| Throughput | 1,641 requests per second |
| Errors | 0 of 106,829 requests |
| Product page p50 / p95 / p99 | 9.8 / 16.5 / 21.3 ms |
| Checkout p50 / p95 / p99 | 86.6 / 108.6 / 124.5 ms |
| API container CPU, average | 3.4 cores |
| MySQL container CPU, average | 6.4 cores |

Both thresholds in the script pass: product p95 under 500 ms and checkout p95 under 1.5 s. Checkout is slower by design. It runs four short transactions in sequence: reserve stock and create the pending order, record the payment attempt, charge, then finalise with a compare-and-set.

## 2. Where the time goes

**The database works about twice as hard as the API.** So the first gains come from what we ask the database, not from how fast the API code runs.

| Request | SQL selects | Bytes MySQL sends | Bytes in the JSON response |
|---|---|---|---|
| Product page | 4.1 | 35,945 | 1,693 |
| Product list (15) | 4.0 | 51,267 | not measured |

**The product page reads 21 times more data than it returns.** The single-product loader join-fetches about twenty collections in one statement (`ProductRepositoryImpl.java:1127`). The result is a Cartesian product: every image row is multiplied by every attribute, variant, price and description row. With one test product that costs a few milliseconds. With a real catalog of 10 images, 30 attribute values and 5 variants, the same query returns thousands of rows for one page.

## 3. What I would change, in order of payoff

1. **Split the product loader.** Load the product, then each collection in its own query with `@BatchSize`, or with entity graphs per screen. Expected result: bytes read close to the response size, and flat latency as the catalog grows. This is the biggest win, and it is pure Java.
2. **Cache what rarely changes.** Product, category and store reads are about 90% of traffic. Cache the rendered DTO per store and language, evict on admin update, and set HTTP cache headers so the CDN absorbs most reads.
3. **Stop recompiling Drools rules on every quote.** Rules are rebuilt for each shipping quote and cart total. `DroolsBeanFactory` builds a new `KieContainer` per call. Compile once at startup, or replace the two rules with tested Java strategies.
4. **Bound and measure the DB pool per instance.** The pool panel in Grafana shows active and pending connections. Size the pool from that, not from guesses.
5. **Then profile CPU.** Only after the database stops being the bottleneck do API CPU profiles become meaningful.

## 4. Where Rust fits, and where it does not

**Rewriting the API in Rust would not move these numbers much.** At a p50 of about 10 ms with the database doing most of the work, a faster language saves a few milliseconds of serialisation and framework overhead. It would also throw away a working domain model and every fix in this repository.

**Rust earns its place in CPU-bound, isolated components with a narrow interface.** These are the candidates in this platform:

| Component | Today | Why Rust helps | How I would do it |
|---|---|---|---|
| Image resizing on upload | `ProductFileManagerImpl` crops and resizes with Java image APIs inside the API process | CPU and memory heavy, and it competes with checkout for the same threads | A small Rust service (for example `image` or `fast_image_resize`) behind a queue. It writes thumbnails to object storage |
| Product search | OpenSearch cluster, off by default | A full cluster is heavy for small catalogs | Meilisearch or Tantivy, both written in Rust, as an off-the-shelf engine. No custom Rust code needed |
| Edge rate limiting and bot filtering | None in the API | Runs on every request and needs low latency | A Rust proxy such as Pingora in front of the API, or the CDN's WAF |
| Price and tax calculation | Java, with `double` in places | Correctness matters more than speed here | Fix the money types in Java first. Rust adds nothing here |

**The rule I would give the team:** measure first, fix the query, cache the read, and reach for Rust only for a component that is CPU-bound, has a stable interface and can be deployed on its own.

## 5. How to reproduce

Start the stack (see `deploy/README.md`), seed it with `scripts/demo-checkout.sh`, then run the load test.

```bash
docker run --rm -i --network host -e BASE=http://localhost:8080 grafana/k6:1.3.0 run - < deploy/loadtest/k6-shop.js
```

Watch the Grafana dashboard at http://localhost:3001 while it runs.

## 6. After the third pass: the catalog fix, measured

The first measurement used one product, so it could not show the catalog problem. This one used `scripts/seed-catalog.sh` with 1,001 products on MySQL 8.4, the same database for the old and new builds.

| Measure | Before | After |
|---|---|---|
| MySQL bytes read per 15-product listing page | 96.6 MB | 126 KB |
| Listing p50 | 5.8 s | 26 ms |
| k6 mixed load | Old build ran out of memory and was killed | 749 rps; products p95 62 ms, product p95 35 ms, category p95 24 ms |
| Product page, rich product (10 images, 5 variants), MySQL bytes | 165.7 KB | 62.3 KB |
| Product page, small product, MySQL bytes | 54.9 KB | 59.7 KB |
| Unknown slug | 500, 31.9 KB | 404, 8.0 KB |

**What changed.** The listing pages over product ids in SQL and then batch-loads the collections, and tests fail if a query pages in memory over a collection fetch. The product page loads each collection with its own statement instead of one Cartesian join.

**Honest limits.** On a small product the new loader reads slightly more, because per-statement overhead (about 4 KB) dominates. The gain is on rich products and listings, which is where real catalogs live. Latencies at this size are within the noise of a shared Docker VM; the byte counts are the meaningful figures.

**Connections.** Requests no longer hold a database connection for their whole life: Hibernate releases it after each transaction, so a slow payment gateway cannot pin connections. A pool wait longer than 2 s answers 503 with Retry-After instead of queueing for 30 s.

