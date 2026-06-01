# TestKit

Unified, code-first backend testing toolkit for Java. One fluent DSL for HTTP testing, database seeding, load testing, security probes, contract testing, mock servers, and message queue assertions — all sharing a single execution context.

**Java 21 · Maven multi-module · JUnit 5**

---

## Table of Contents

- [Why TestKit](#why-testkit)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Modules](#modules)
  - [Core — pipeline engine](#core--pipeline-engine)
  - [API — HTTP testing](#api--http-testing)
  - [Mock — embedded stub server](#mock--embedded-stub-server)
  - [Seed — test data generation](#seed--test-data-generation)
  - [DB — database assertions](#db--database-assertions)
  - [Security — automated probes](#security--automated-probes)
  - [Load — performance testing](#load--performance-testing)
  - [Contract — consumer-driven contracts](#contract--consumer-driven-contracts)
  - [Queue — Kafka & RabbitMQ](#queue--kafka--rabbitmq)
  - [Spring Boot integration](#spring-boot-integration)
- [The Full Chain](#the-full-chain)
- [Extension Points](#extension-points)
- [Running the Workshop](#running-the-workshop)

---

## Why TestKit

A typical Java backend project today uses separate tools for each testing concern:

| Concern | Typical tool |
|---|---|
| HTTP API tests | RestAssured |
| Load tests | k6 / Gatling (different language) |
| Database seeding | Custom SQL scripts |
| Mock HTTP servers | WireMock (manual lifecycle) |
| Contract tests | Pact (separate CI integration) |
| Security scanning | OWASP ZAP (separate process) |
| Queue assertions | Ad-hoc consumer code |

TestKit collapses all of these into one Java DSL. A single import, a single builder chain, one shared context that flows between steps, one report.

---

## Installation

### All modules (recommended for new projects)

```xml
<dependency>
    <groupId>io.testkit</groupId>
    <artifactId>testkit-all</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### Individual modules (pick only what you need)

```xml
<dependency>
    <groupId>io.testkit</groupId>
    <artifactId>testkit-core</artifactId>   <!-- required by all others -->
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>

<!-- add any combination of: -->
<artifactId>testkit-api</artifactId>        <!-- HTTP testing -->
<artifactId>testkit-seed</artifactId>       <!-- test data seeding -->
<artifactId>testkit-db</artifactId>         <!-- database assertions -->
<artifactId>testkit-mock</artifactId>       <!-- embedded WireMock -->
<artifactId>testkit-security</artifactId>   <!-- security probes -->
<artifactId>testkit-load</artifactId>       <!-- load testing -->
<artifactId>testkit-contract</artifactId>   <!-- contract testing -->
<artifactId>testkit-queue</artifactId>      <!-- Kafka / RabbitMQ -->
<artifactId>testkit-spring</artifactId>     <!-- Spring Boot autoconfiguration -->
```

---

## Quick Start

```java
import io.testkit.TestKitDsl;

TestKitDsl.test("Create and fetch order")
    .config(c -> c.baseUrl("http://localhost:8080").failFast(true))
    .api(a -> a
        .POST("/api/orders")
        .json("""{"productId":"P-1","qty":2}""")
        .expect(201)
        .extract("orderId", "$.id"))          // stores value in shared context
    .api("Fetch order", a -> a
        .GET("/api/orders/{id}")
        .pathParam("id", ctx -> ctx.get("orderId"))  // reads from context at run time
        .expect(200)
        .assertJsonPath("$.status", "PENDING"))
    .runAndAssert();                           // throws if any step fails
```

Every step shares one `TestKitContext`. Values written in one step (via `.extract()`) are available to all subsequent steps.

---

## Modules

### Core — pipeline engine

The engine all other modules are built on. You can use it directly to write custom steps.

```java
// Custom step
class GenerateTokenStep implements TestKitStep {
    @Override public String name() { return "Generate token"; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        ctx.put("token", "Bearer " + UUID.randomUUID());
        return StepResult.passed(name(), Duration.ZERO).build();
    }
}

TestKitResult result = TestKit.test("Auth flow")
    .config(c -> c.failFast(true))     // stop on first failure (default)
    // .config(c -> c.failFast(false)) // run all steps, collect all failures
    .step(new GenerateTokenStep())
    .step(new UseTokenStep())
    .run();

result.passed();       // boolean
result.passCount();    // int
result.failCount();    // int
result.steps();        // List<StepResult> — each has name, status, duration, detail
```

**Context API:**

```java
ctx.put("key", value);              // write
ctx.get("key");                     // read — throws if missing (shows available keys)
ctx.find("key");                    // read — returns Optional, never throws
ctx.has("key");                     // boolean check
ctx.resolve(ctx -> ctx.get("key")); // late-binding: lambda runs at execution time
```

**Inline function steps (`fn`):**

For logic that doesn't justify a dedicated step class, `.fn()` wraps any Java lambda directly into the pipeline:

```java
TestKit.test("Order flow")
    // Plain Runnable — no context needed
    .fn("clear Redis cache", () -> redisClient.flushDb())

    // Context-aware — read/write shared state between steps
    .fn("resolve tenant", ctx -> {
        String id = tenantService.lookup("acme");
        ctx.put("tenantId", id);
    })

    // Use the extracted value in a later step
    .api("fetch tenant", a -> a
        .GET("/tenants/{id}")
        .pathParam("id", ctx -> ctx.get("tenantId"))
        .expect(200))

    // Assertion step — any thrown exception (checked or AssertionError) → FAILED
    .fn("verify audit log", ctx -> {
        String orderId = ctx.get("orderId");
        assertThat(auditLog.contains(orderId)).isTrue();
    })
    .runAndAssert();
```

`FnStep.Action` is a `@FunctionalInterface` that may throw any checked exception — no need to wrap in `try/catch`. Use `.fn()` for setup, teardown, custom assertions, or utility calls; write a full `TestKitStep` class only when the logic is complex enough to deserve one.

---

**Reporters:**

```java
// Console output is on by default. Add JSON output alongside it:
TestKit.test("my suite")
    .reporter(new JsonReporter(Path.of("target/testkit-report.json")))
    .step(...)
    .run();
```

---

### API — HTTP testing

```java
.api(a -> a
    .POST("/api/orders")                       // GET / POST / PUT / PATCH / DELETE
    .header("X-Tenant", "acme")
    .bearerToken("my-jwt-token")
    .json("""{"productId":"p1","qty":2}""")    // request body
    .expect(201)                               // assert status code
    .assertJsonPath("$.status", "PENDING")     // JSONPath equality
    .assertJsonPathPresent("$.id")             // JSONPath must resolve to non-null
    .assertHeader("Content-Type", "application/json")
    .assertContentType("application/json")     // shorthand
    .extract("orderId", "$.id")               // store $.id into ctx["orderId"]
    .extract("status",  "$.status"))
```

**Dynamic path parameters** (late binding — reads context at run time, not build time):

```java
.api(a -> a
    .GET("/api/orders/{id}")
    .pathParam("id", ctx -> ctx.get("orderId"))  // resolved after previous step ran
    .expect(200))
```

**Query parameters and form data:**

```java
.api(a -> a
    .GET("/api/products")
    .query("category", "electronics")
    .query("limit", "10")
    .expect(200))

.api(a -> a
    .POST("/api/login")
    .formParam("username", "admin")
    .formParam("password", "secret")
    .expect(200))
```

**Per-step config override:**

```java
ApiConfig cfg = ApiConfig.builder()
    .baseUrl("https://external-service.com")
    .timeout(Duration.ofSeconds(30))
    .verifySsl(false)
    .build();

new ApiStep("Call external service", a -> a.GET("/health").expect(200), cfg)
```

---

### Mock — embedded stub server

Start a WireMock server as part of your test pipeline. Its URL is stored in context so later steps can point to it automatically.

```java
.mock(m -> m
    .contextKey("paymentServiceUrl")           // ctx["paymentServiceUrl"] = "http://localhost:PORT"
    .stub(s -> s
        .POST("/payments/charge")
        .withBody("\"amount\"")                // only match if body contains this string
        .willReturn(201, """{"chargeId":"c-1","status":"OK"}"""))
    .stub(s -> s
        .GET("/payments/health")
        .delay(50)                             // artificial latency in ms
        .willReturn(200, """{"status":"UP"}""")))
```

The server starts when the step runs and shuts down at suite end. Use `port(9090)` to fix the port, or omit it for a random port (recommended in CI).

For tests that manage WireMock themselves (e.g. `@BeforeEach`), you can still use `ApiStep` directly with `ApiConfig.builder().baseUrl(baseUrl).build()`.

---

### Seed — test data generation

Generate and insert rows into any JDBC database.

**Define a factory:**

```java
class UserFactory extends DataFactory<Map<String, Object>> {
    @Override
    protected void define() {
        field("id",       FieldSupplier.uuid());
        field("email",    FieldSupplier.email());
        field("name",     FieldSupplier.name());
        field("role",     FieldSupplier.fixed("USER"));   // same value every time
        field("seq",      FieldSupplier.sequence());      // 1, 2, 3, ...
    }
}
```

**Available `FieldSupplier` generators:**

| Method | Example output |
|---|---|
| `uuid()` | `"a3f1b2c4-..."` |
| `email()` | `"john.doe@example.org"` |
| `name()` | `"Alice Smith"` |
| `firstName()` / `lastName()` | `"Alice"` / `"Smith"` |
| `phone()` | `"+1-555-234-5678"` |
| `password()` | `"K9mP!x2nQr3v"` |
| `address()` | `"123 Main St, Springfield"` |
| `companyName()` | `"Acme Corp"` |
| `lorem(5)` | 5 random words joined by spaces |
| `sequence()` | `1L`, `2L`, `3L`, … |
| `fixed("ADMIN")` | `"ADMIN"` always |

**Use in a step:**

```java
.seed(s -> s
    .dataSource(dataSource)
    .table("users").fromFactory(new UserFactory(), 5)     // insert 5 rows
    .table("products").fromFixture("fixtures/seed.json")  // load from classpath JSON/YAML
    .table("tags").fromRows(List.of(                      // literal rows
        Map.of("id", "1", "name", "java"),
        Map.of("id", "2", "name", "testing")))
    .truncate("audit_log")                                // clear a table first
    .sql("UPDATE config SET value=? WHERE key=?",         // raw SQL
         "dark", "theme"))
```

Generated keys are stored in context: `ctx.get("seed.users.keys")` and `ctx.get("seed.users.rows")`.

---

### DB — database assertions

Assert database state after an API call or seed step.

```java
.db(d -> d
    .dataSource(dataSource)
    .assertExists("orders", "status = ?", "PENDING")      // at least one row matches
    .assertNotExists("users", "email = ?", "ghost@x.com") // no row matches
    .assertCount("orders", 3)                             // exactly N rows in table

    // Query + row-level assertions
    .query("SELECT * FROM orders WHERE id = ?", "ORD-1")
        .assertRowCount(1)
        .assertColumn("status", "PENDING")        // first row, column value equals
        .assertColumnNotNull("created_at")        // first row, column is not null
        .assertColumnNull("deleted_at")           // first row, column is null
        .end()                                    // close query block, return to db()

    // Extract a scalar value into context for later steps
    .extract("latestOrderId",
             "SELECT id FROM orders ORDER BY created_at DESC LIMIT 1"))
```

---

### Security — automated probes

Run automated OWASP-inspired security checks against your endpoints.

```java
.security(s -> s
    .baseUrl("http://localhost:8080")          // or inherit from suite config
    .sqlInjection("/api/search")               // fires 11 SQL payloads, checks for DB errors
    .xss("/api/feedback")                      // reflected XSS payload detection
    .authBypass("/api/admin/users")            // checks if endpoint accessible without auth
    .rateLimit("/api/auth/login")              // checks if 429 is ever returned under load
    .sensitiveDataExposure("/api/users/me")    // scans response for PII, keys, stack traces
    .failOnSeverity(SecurityFinding.Severity.HIGH))  // CRITICAL > HIGH > MEDIUM > LOW > INFO
```

**Severity levels and what triggers them:**

| Probe | Triggers on | Severity |
|---|---|---|
| SQL Injection | 500 response + DB error keyword in body | HIGH |
| XSS | Payload reflected verbatim in HTML/JS response | HIGH |
| Auth Bypass | 200/201 returned with no or invalid auth | CRITICAL |
| Rate Limit | No 429 after 50 rapid requests | MEDIUM |
| Sensitive Data | AWS key, credit card, SSN, private key, stack trace in body | CRITICAL / MEDIUM |

**Full scan shorthand:**

```java
.security(s -> s.fullScan("/api/orders").failOnAnyFinding())
```

**Custom probe:**

```java
.security(s -> s.probe("/api/payments", new SecurityProbe() {
    @Override public String name() { return "CORS check"; }

    @Override
    public List<SecurityFinding> probe(String path, ApiConfig cfg) {
        // send OPTIONS request, inspect Access-Control-Allow-Origin
        return List.of(); // no findings
    }
}))
```

---

### Load — performance testing

Uses Java 21 virtual threads and HDR histogram. Each virtual user runs its own iteration loop for the specified duration.

```java
.load(l -> l
    .scenario("Checkout under load")
    .POST("/api/checkout")
    .json("""{"cartId":"cart-1"}""")
    .expect(200)
    .virtualUsers(100)              // concurrent virtual users
    .rampUp(Duration.ofSeconds(10)) // gradually start VUs over this duration
    .duration(60)                   // sustain load for N seconds
    .rps(500)                       // cap throughput (token bucket, across all VUs)
    .p50Under(100)                  // SLO: median latency < 100ms
    .p99Under(500)                  // SLO: 99th percentile < 500ms
    .maxUnder(2000)                 // SLO: no single request > 2000ms
    .errorRateUnder(0.5))           // SLO: < 0.5% errors
```

All SLO thresholds are checked at the end. Every violation is reported in one combined exception — it never short-circuits on the first failure.

**Run a load test standalone (without the pipeline DSL):**

```java
LoadScenario scenario = LoadScenario.http("Ping", a -> a.GET("/ping").expect(200), apiConfig);
LoadConfig config = LoadConfig.builder().virtualUsers(10).duration(Duration.ofSeconds(5)).build();
LoadMetrics metrics = new LoadRunner(config, scenario).run();

System.out.println("p99: " + metrics.p99() + "ms");
System.out.println("errors: " + metrics.errorCount());
System.out.println("rps: " + metrics.actualRps());
```

---

### Contract — consumer-driven contracts

Pact-style contract testing. The consumer defines its expectations and saves them as a JSON file. The provider loads that file and proves it can satisfy the contract — without the two teams needing to coordinate test runs.

**Consumer side — define and save:**

```java
.contract(c -> c
    .consumer("order-service")
    .provider("inventory-service")
    .describe("Get product stock for SKU-1")
    .apiConfig(apiConfig)
    .request(new ApiBuilder().GET("/api/inventory/SKU-1"))
    .expectStatus(200)
    .expectBodyField("$.sku", "SKU-1")   // field must equal this value
    .expectBodyField("$.stock")           // field must be present
    .saveContractTo(Path.of("contracts/order-inventory.json")))
```

**Provider side — verify:**

```java
.contract(c -> c
    .verifyContract(Path.of("contracts/order-inventory.json"))
    .apiConfig(providerApiConfig)
    .request(new ApiBuilder().GET("/api/inventory/SKU-1")))
```

If the provider's response doesn't match the saved contract, the step fails with a detailed message listing every violation.

Contracts are plain JSON — commit them to a shared repo or artifact store so consumer and provider teams can evolve independently.

---

### Queue — Kafka & RabbitMQ

**Kafka:**

```java
.queue(q -> q
    .kafka("localhost:9092")
    .produce("order-commands", """{"action":"CREATE","productId":"P-1"}""")
    .produce("order-commands", "key-123", """{"action":"UPDATE","status":"SHIPPED"}""")
    .assertMessageOnTopic("order-events", "\"status\":\"CREATED\"", Duration.ofSeconds(5))
    .consumeToContext("lastEvent", "order-events", Duration.ofSeconds(3)))
    // ctx.get("lastEvent") returns a QueueMessage
```

Each `assertMessageOnTopic` starts a fresh consumer with a random group ID, so tests never interfere with each other's offsets.

**RabbitMQ:**

```java
.queue(q -> q
    .rabbit("localhost")
    .purgeQueue("email-inbox")
    .publishToQueue("email-inbox", """{"to":"user@example.com","subject":"Welcome"}""")
    .assertMessageOnQueue("email-inbox", "user@example.com", Duration.ofSeconds(3)))
```

---

### Spring Boot integration

Add `testkit-spring` to get zero-config autowiring in `@SpringBootTest` tests.

**`application.yml`:**

```yaml
testkit:
  base-url: http://localhost:${local.server.port}
  timeout: 15s
  fail-fast: true
```

**Test class:**

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderApiTest {

    @Autowired TestKitFactory testKit;

    @Test
    void createOrder() {
        testKit.test("Create order")
            .api(a -> a
                .POST("/api/orders")
                .json("""{"productId":"P-1","qty":1}""")
                .expect(201))
            .runAndAssert();
    }
}
```

`TestKitFactory` pre-wires the `baseUrl` from the running server's random port — no manual port plumbing needed.

Override either bean in your test config if needed:

```java
@TestConfiguration
class MyTestConfig {
    @Bean
    TestKitConfig testKitConfig() {
        return TestKitConfig.builder().baseUrl("http://my-custom-url").build();
    }
}
```

---

## The Full Chain

This is what a realistic end-to-end test looks like with every module:

```java
TestKitDsl.test("Order Placement E2E")
    .config(c -> c.baseUrl("http://localhost:8080").failFast(true))

    // 1. Start a mock payment service
    .mock(m -> m
        .contextKey("paymentServiceUrl")
        .stub(s -> s.POST("/payments/charge").willReturn(201, """{"chargeId":"c-1"}""")))

    // 2. Seed a test user
    .seed(s -> s
        .dataSource(dataSource)
        .table("users").fromFactory(new UserFactory(), 1))

    // 3. Create an order (extracts orderId into context)
    .api("Create order", a -> a
        .POST("/api/orders")
        .json("""{"productId":"P-1","qty":1}""")
        .expect(201)
        .extract("orderId", "$.id"))

    // 4. Fetch it back using the extracted ID
    .api("Fetch order", a -> a
        .GET("/api/orders/{id}")
        .pathParam("id", ctx -> ctx.get("orderId"))
        .expect(200)
        .assertJsonPath("$.status", "PENDING"))

    // 4b. Inline logic that reads the extracted ID — no custom step class needed
    .fn("tag order for audit", ctx -> {
        auditLog.record(ctx.get("orderId"), "placed");
    })

    // 5. Assert it was persisted to the database
    .db(d -> d
        .dataSource(dataSource)
        .assertExists("orders", "id = ?", ctx -> ctx.get("orderId"))
        .query("SELECT status FROM orders WHERE id = ?", ctx -> ctx.get("orderId"))
            .assertColumn("status", "PENDING")
            .end())

    // 6. Assert an event was published to Kafka
    .queue(q -> q
        .kafka("localhost:9092")
        .assertMessageOnTopic("order-events", "PENDING", Duration.ofSeconds(5)))

    // 7. Security scan
    .security(s -> s
        .sqlInjection("/api/orders")
        .authBypass("/api/admin/orders")
        .failOnSeverity(SecurityFinding.Severity.HIGH))

    // 8. Load test the endpoint
    .load(l -> l
        .POST("/api/orders").json("""{"productId":"P-1","qty":1}""").expect(201)
        .virtualUsers(20).duration(10).p99Under(500).errorRateUnder(1.0))

    .runAndAssert();
```

---

## Extension Points

### Custom step

```java
public class WaitForIndexStep implements TestKitStep {
    @Override public String name() { return "Wait for search index"; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        // your logic
        return StepResult.passed(name(), Duration.ofMillis(elapsed)).build();
        // or: return StepResult.failed(name(), duration).detail("reason").build();
    }
}

TestKitDsl.test("Search flow").step(new WaitForIndexStep()).api(...).run();
```

### Custom reporter

```java
public class SlackReporter implements TestKitReporter {
    @Override
    public void onSuiteEnd(TestKitResult result) {
        String msg = result.passed() ? "✓ All tests passed" : "✗ " + result.failCount() + " tests failed";
        // post to Slack webhook
    }
}

TestKit.test("suite").reporter(new SlackReporter(webhookUrl)).step(...).run();
```

### Custom security probe

```java
SecurityProbe csrfProbe = (path, cfg) -> {
    // send a state-changing request without a CSRF token
    // return findings if it succeeds when it shouldn't
    return List.of();
};

.security(s -> s.probe("/api/transfer", csrfProbe).failOnSeverity(CRITICAL))
```

---

## Running the Workshop

The `testkit-workshop` module contains eight annotated exercises:

| Exercise | What it covers |
|---|---|
| `Ex01_CoreContextAndCustomStep` | Context propagation, custom steps, inline `fn()` steps, failFast |
| `Ex02_MockAndApi` | WireMock stubs, API assertions, extract + late binding |
| `Ex03_SecurityProbes` | Each built-in probe, custom probe SPI |
| `Ex04_LoadTesting` | LoadRunner, LoadMetrics, Threshold SLOs |
| `Ex05_ContractTesting` | Consumer defines contract, provider verifies |
| `Ex06_SeedAndDb` | DataFactory, SeedBuilder, DbBuilder |
| `Ex07_FullChain` | All modules combined; `fn()` replacing an anonymous step mid-chain |

Run a single exercise:

```bash
mvn test -pl testkit-workshop -Dtest=Ex01_CoreContextAndCustomStep
```

Run all exercises:

```bash
mvn test -pl testkit-workshop
```

Build everything:

```bash
mvn install -DskipTests
```

> Requires Java 21. Preview features are enabled automatically via the parent POM.
