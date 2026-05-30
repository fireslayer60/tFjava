# TestKit — Framework Design Document

**Version:** 0.1.0-SNAPSHOT  
**Java:** 21 (preview features enabled)  
**Build:** Maven multi-module  
**Group:** `io.testkit`

---

## Table of Contents

1. [Project Vision](#1-project-vision)
2. [High-Level Design (HLD)](#2-high-level-design)
   - 2.1 Goals and Non-Goals
   - 2.2 Architecture Overview
   - 2.3 Module Dependency Graph
   - 2.4 Core Abstractions
   - 2.5 Execution Pipeline
   - 2.6 Cross-Cutting Concerns
3. [Low-Level Design (LLD) — Module by Module](#3-low-level-design)
   - 3.1 testkit-core
   - 3.2 testkit-seed
   - 3.3 testkit-api
   - 3.4 testkit-load
   - 3.5 testkit-db
   - 3.6 testkit-contract
   - 3.7 testkit-security
   - 3.8 testkit-mock
   - 3.9 testkit-queue
   - 3.10 testkit-spring
4. [Key Algorithms and Design Decisions](#4-key-algorithms-and-design-decisions)
5. [Dependency Manifest](#5-dependency-manifest)
6. [Extension Points (SPI)](#6-extension-points)
7. [Full API Reference (DSL Cheat Sheet)](#7-full-api-reference)

---

## 1. Project Vision

TestKit is a **unified, code-first backend testing toolkit** for Java. It addresses the fragmentation problem in backend testing: a typical project today uses separate tools for HTTP testing (RestAssured), load testing (k6/Gatling), database seeding (Flyway scripts), mock servers (WireMock manually), queue testing (ad-hoc scripts), contract testing (Pact), and security scanning (OWASP ZAP). Engineers context-switch between DSLs, languages, and CI integrations for every testing concern.

TestKit collapses all of these into **one fluent Java DSL**. A single import, a single builder chain, one execution model, and one report. Think "Playwright for backend devs" — where Playwright unified browser automation across browsers and test types, TestKit unifies every backend testing concern under a single coherent framework.

**The canonical full-stack test reads like a specification:**

```java
TestKitDsl.test("Order Placement E2E")
    .config(c -> c.baseUrl("http://localhost:8080").failFast(true))
    .seed(s -> s.dataSource(ds).table("users").fromFactory(new UserFactory(), 1))
    .mock(m -> m.stub(s -> s.GET("/payments/health").willReturn(200, "{\"status\":\"UP\"}")))
    .api(a -> a.POST("/api/orders").json("{\"qty\":1}").expect(201).extract("orderId", "$.id"))
    .db(d -> d.dataSource(ds).query("SELECT status FROM orders WHERE id=?", ctx->ctx.get("orderId"))
              .assertColumn("status","PENDING").end())
    .queue(q -> q.kafka("localhost:9092").assertMessageOnTopic("order-events","PENDING",Duration.ofSeconds(5)))
    .security(s -> s.sqlInjection("/api/orders").authBypass("/api/admin"))
    .contract(c -> c.consumer("order-service").provider("payment-service")
                    .request(r -> r.GET("/payments/health")).expectStatus(200))
    .load(l -> l.POST("/api/orders").json("{\"qty\":1}").virtualUsers(50).duration(30).p99Under(500))
    .runAndAssert();
```

---

## 2. High-Level Design

### 2.1 Goals and Non-Goals

**Goals**
- Single-import entry point for all backend testing concerns
- Fluent chainable builder DSL (no XML, no YAML, no separate config files required)
- Shared mutable context flows between steps — extract values in one step, use in the next
- Modular Maven artifacts — teams adopt only the modules they need
- Spring Boot autoconfiguration for zero-config integration
- Pluggable reporters (console, JSON, custom SPI)
- Pluggable security probes (custom SPI)
- Java 21 virtual threads for load testing concurrency

**Non-Goals**
- Not a test runner — runs inside JUnit 5 (or any JVM test framework)
- Not a monitoring/APM tool — tests are transient, not persistent
- Not a record-replay proxy — stubs are code-declared, not recorded
- Not opinionated about assertion libraries — uses its own lightweight assertions internally, returns results for custom assertions

---

### 2.2 Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        User Test Code                               │
│          TestKitDsl.test("...")                                      │
│             .config(...).seed(...).mock(...).api(...)                │
│             .load(...).db(...).queue(...).security(...)              │
│             .contract(...).runAndAssert()                           │
└─────────────────────────┬───────────────────────────────────────────┘
                          │ compiles to a List<TestKitStep>
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    TestKit (core engine)                            │
│  ┌──────────────────┐  ┌─────────────────┐  ┌───────────────────┐  │
│  │  TestKitConfig   │  │ TestKitContext   │  │ List<TestKitStep> │  │
│  │  baseUrl         │  │ HashMap<k,v>     │  │  (ordered)        │  │
│  │  timeout         │  │ put/get/resolve  │  │                   │  │
│  │  failFast        │  └────────┬────────┘  └────────┬──────────┘  │
│  └──────────────────┘           │ flows through all   │             │
│                                 ▼                     ▼             │
│                         ┌───────────────────────────────────┐      │
│                         │         TestKitRunner             │      │
│                         │  for each step:                   │      │
│                         │    reporters.onStepStart()        │      │
│                         │    step.execute(ctx) → StepResult │      │
│                         │    reporters.onStepEnd()          │      │
│                         │    if failed && failFast → abort  │      │
│                         └──────────────┬────────────────────┘      │
│                                        │                            │
│                         ┌──────────────▼────────────────────┐      │
│                         │    List<TestKitReporter>          │      │
│                         │  ConsoleReporter | JsonReporter   │      │
│                         │  (or custom SPI implementations)  │      │
│                         └───────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────┘
                          │
      ┌───────────────────┼──────────────────────────────┐
      │                   │                              │
      ▼                   ▼                              ▼
┌──────────┐     ┌──────────────┐            ┌─────────────────┐
│SeedStep  │     │  ApiStep     │            │  LoadStep       │
│JdbcSeeder│     │ OkHttpClient │            │ LoadRunner      │
│ Faker    │     │ JSONPath     │            │ VirtualThreads  │
│DataFactory     │              │            │ HDR Histogram   │
└──────────┘     └──────────────┘            └─────────────────┘
      │                   │                              │
┌──────────┐     ┌──────────────┐            ┌─────────────────┐
│ DbStep   │     │SecurityStep  │            │ MockStep        │
│ JDBC     │     │ OkHttpClient │            │ WireMock        │
│ RowSet   │     │ 5 probes     │            │ ManagedServer   │
└──────────┘     └──────────────┘            └─────────────────┘
      │                   │
┌──────────┐     ┌──────────────┐
│QueueStep │     │ContractStep  │
│ Kafka    │     │ ApiBuilder   │
│ RabbitMQ │     │ Contract JSON│
└──────────┘     └──────────────┘
```

---

### 2.3 Module Dependency Graph

```
testkit-parent (BOM / aggregator)
│
├── testkit-core         ← no inter-module deps, only external libs
│   ├── slf4j-api
│   ├── jackson-databind
│   ├── commons-lang3
│   └── junit-jupiter-api (provided)
│
├── testkit-seed         depends on → testkit-core
│   ├── HikariCP
│   ├── javafaker
│   └── snakeyaml
│
├── testkit-api          depends on → testkit-core
│   ├── okhttp
│   └── json-path
│
├── testkit-load         depends on → testkit-core, testkit-api
│   └── HdrHistogram
│
├── testkit-db           depends on → testkit-core
│   └── HikariCP
│
├── testkit-contract     depends on → testkit-core, testkit-api
│   (no extra runtime deps)
│
├── testkit-security     depends on → testkit-core, testkit-api
│   (uses OkHttp transitively via testkit-api)
│
├── testkit-mock         depends on → testkit-core
│   └── wiremock
│
├── testkit-queue        depends on → testkit-core
│   ├── kafka-clients
│   └── amqp-client
│
└── testkit-spring       depends on → testkit-core + all modules
    └── spring-boot-autoconfigure
```

**Key invariant:** `testkit-core` has **zero** dependencies on other TestKit modules. All specialised modules depend downward on `testkit-core`. This prevents circular dependencies and keeps the core engine swappable.

---

### 2.4 Core Abstractions

Four interfaces/classes form the entire extension model:

| Abstraction | Role | Extension point |
|---|---|---|
| `TestKitStep` | Single executable unit of work | Implement to add custom steps |
| `TestKitContext` | Mutable shared state bag | Used by all steps; keys are plain strings |
| `StepResult` | Immutable result of one step | PASSED / FAILED / SKIPPED + detail + duration |
| `TestKitReporter` | Observe suite lifecycle events | Implement for custom output formats |

```
TestKitStep (interface)
  name()    → String          human-readable step label
  execute(TestKitContext) → StepResult

TestKitReporter (interface)
  onSuiteStart(String suiteName)
  onStepStart(String stepName)
  onStepEnd(StepResult result)
  onSuiteEnd(TestKitResult result)

SecurityProbe (interface — in testkit-security)
  name()    → String
  probe(String targetPath, ApiConfig config) → List<SecurityFinding>
```

---

### 2.5 Execution Pipeline

When `.run()` is called on `TestKit`, the following sequence occurs:

```
1. TestKitRunner is constructed with (suiteName, steps, config, reporters)
2. A fresh TestKitContext is created — empty HashMap
3. reporters.onSuiteStart(suiteName)
4. For each TestKitStep in the ordered list:
   a. If a previous step failed AND failFast=true → mark step SKIPPED, continue
   b. reporters.onStepStart(step.name())
   c. step.execute(ctx) called
      - Step reads from ctx (previous extractions)
      - Step does its work (HTTP call, DB query, load run, etc.)
      - Step writes back to ctx (extractions, generated IDs, etc.)
      - Returns StepResult(PASSED/FAILED, duration, detail)
   d. If step throws TestKitException → wrapped in FAILED StepResult
   e. If step throws any other Exception → wrapped in FAILED StepResult + logged
   f. reporters.onStepEnd(result)
   g. if result.failed() && config.failFast() → set aborted=true
5. Duration total = now - suiteStart
6. TestKitResult = (suiteName, List<StepResult>, totalDuration)
   - passed = no step has status FAILED
7. reporters.onSuiteEnd(suiteResult)
8. Return TestKitResult
```

`.runAndAssert()` simply calls `run().assertPassed()`, which throws `TestKitException` if any step failed.

---

### 2.6 Cross-Cutting Concerns

**Context propagation** is the backbone of inter-step communication. Every step receives the *same* `TestKitContext` instance. A step can call `ctx.put("orderId", "abc")` and a later step can call `ctx.get("orderId")`. Dynamic resolution is available via `ctx.resolve(valueOrFn)` — if the value is a `Function<TestKitContext, T>`, it is applied at execution time rather than at builder construction time.

**Fail-fast vs continue-on-failure** is controlled by `TestKitConfig.failFast` (default: `true`). When `true`, the first FAILED step causes all remaining steps to be SKIPPED. When `false`, all steps run regardless of failures, and the final `TestKitResult.passed()` returns false if any step failed.

**Reporting** is fully decoupled. `TestKitRunner` calls reporter hooks at four lifecycle points. If no reporter is registered, a `ConsoleReporter` is injected automatically. Multiple reporters can be registered simultaneously (e.g. console output + JSON file simultaneously).

**Exception handling** is consistent: `TestKitException` (unchecked, framework-native) signals intentional test failures. Any other `Exception` is caught, logged at ERROR level, and converted to a FAILED `StepResult` — the runner never lets an unexpected exception propagate out of a step.

---

## 3. Low-Level Design

### 3.1 testkit-core

**Package:** `io.testkit.core`  
**Purpose:** The framework engine — config, context, step contract, runner, results, reporters, JUnit 5 integration.

---

#### `TestKit.java`

The primary user-facing builder and entry point.

```
Fields:
  String suiteName
  TestKitConfig config           (default: TestKitConfig.defaults())
  List<TestKitStep> steps        (ArrayList, ordered)
  List<TestKitReporter> reporters (ArrayList)

Static factories:
  TestKit.test(String) → TestKit
  TestKit.of(String)   → TestKit   (alias)

Builder methods (all return this):
  config(Consumer<TestKitConfig.Builder>)
  config(TestKitConfig)
  step(TestKitStep)
  reporter(TestKitReporter)

Terminal methods:
  run()          → TestKitResult   (does not throw on failure)
  runAndAssert() → void            (throws TestKitException on any failure)
  getConfig()    → TestKitConfig   (package-accessible for module builders)
```

The `run()` method instantiates a `TestKitRunner` and delegates immediately. `TestKit` itself holds no execution state — it is a pure builder.

---

#### `TestKitContext.java`

```
Internal state:
  Map<String, Object> store  (HashMap — not thread-safe by design; steps run sequentially)

Methods:
  put(String key, Object value)           → void
  get(String key)                         → T  (throws if missing, includes available keys in message)
  find(String key)                        → Optional<T>  (no throw)
  has(String key)                         → boolean
  resolve(Object valueOrFn)              → T  (if fn is Function<TestKitContext,T>, apply it; else cast)
  snapshot()                             → Map<String,Object>  (unmodifiable copy)
  merge(Map<String,Object>)              → void  (bulk put)
```

**Context key conventions used by built-in modules:**

| Module | Key pattern | Stored value |
|---|---|---|
| seed | `seed.<table>.rows` | `List<Map<String,Object>>` — generated rows |
| seed | `seed.<table>.keys` | `List<Map<String,Object>>` — DB-generated keys |
| api (extract) | user-defined | JSONPath-extracted string from response |
| mock | user-defined via `.contextKey(k)` | base URL string, e.g. `http://localhost:9876` |
| queue (consumeToContext) | user-defined | `QueueMessage` object |
| db (extract) | user-defined | scalar value from first row / first column |

---

#### `TestKitConfig.java`

Immutable value object. Built via inner `Builder`.

```
Fields:
  String baseUrl          (default: "http://localhost:8080")
  Duration defaultTimeout (default: 10 seconds)
  boolean failFast        (default: true)
  boolean verbose         (default: false)
```

`TestKitConfig.defaults()` returns a config with all defaults. `TestKitConfig.builder()` returns a fresh `Builder`.

---

#### `StepResult.java`

Immutable. Built via inner `Builder`.

```
Enum Status: PASSED, FAILED, SKIPPED

Fields:
  String stepName
  Status status
  Duration duration
  String detail      (nullable — human-readable failure message or success note)
  List<String> assertions  (list of assertion descriptions that ran)
  Throwable cause    (nullable — underlying exception if step threw)

Static factories:
  StepResult.passed(name, duration)  → Builder
  StepResult.failed(name, duration)  → Builder
  StepResult.skipped(name)           → StepResult (direct, no builder needed)

Builder methods:
  detail(String)
  assertions(List<String>)
  cause(Throwable)
  build() → StepResult
```

---

#### `TestKitResult.java`

Aggregated result for an entire suite run.

```
Fields:
  String suiteName
  List<StepResult> stepResults   (unmodifiable copy)
  Duration totalDuration
  boolean passed                 (= no step has Status.FAILED)

Methods:
  passed() / failed()
  passCount() / failCount()
  assertPassed()  → throws TestKitException if failed()
```

---

#### `TestKitRunner.java`  (package-private)

Core execution loop. Not exposed to users.

```
Constructor: (suiteName, steps, config, reporters)
  - steps is defensively copied
  - if reporters is empty, injects ConsoleReporter

run() algorithm:
  ctx = new TestKitContext()
  results = []
  suiteStart = Instant.now()
  reporters.forEach(r -> r.onSuiteStart(suiteName))

  for step in steps:
    if aborted:
      results.add(StepResult.skipped(step.name()))
      reporters.forEach(r -> r.onStepEnd(skipped))
      continue

    reporters.forEach(r -> r.onStepStart(step.name()))
    result = executeStep(step, ctx)   ← catches all exceptions
    results.add(result)
    reporters.forEach(r -> r.onStepEnd(result))

    if result.failed() && config.failFast():
      aborted = true

  total = Duration.between(suiteStart, now)
  suiteResult = new TestKitResult(suiteName, results, total)
  reporters.forEach(r -> r.onSuiteEnd(suiteResult))
  return suiteResult

executeStep(step, ctx):
  start = Instant.now()
  try:
    return step.execute(ctx)
  catch TestKitException e:
    duration = elapsed
    return StepResult.failed(name, duration).detail(e.getMessage()).cause(e).build()
  catch Exception e:
    duration = elapsed
    log.error(...)
    return StepResult.failed(name, duration).detail("Unexpected: " + ...).cause(e).build()
```

---

#### `report/TestKitReporter.java`

SPI interface. All four methods have empty default implementations so implementors only need to override what they care about.

```java
interface TestKitReporter {
    default void onSuiteStart(String suiteName) {}
    default void onStepStart(String stepName) {}
    default void onStepEnd(StepResult result) {}
    default void onSuiteEnd(TestKitResult result) {}
}
```

---

#### `report/ConsoleReporter.java`

ANSI colored output. Writes to a `PrintStream` (default: `System.out`).

```
onSuiteStart → prints "▶ TestKit — <name>" in bold cyan + separator line
onStepStart  → prints "  <stepName padded to 45 chars> " (no newline, flushes)
onStepEnd    → appends "✓ PASSED" (green) / "✗ FAILED" (red) / "- SKIPPED" (yellow)
               + duration in ms
               if failed: prints "→ <detail>" in red on next line
onSuiteEnd   → prints separator + summary: "PASSED/FAILED | N passed, M failed | total Xms"
```

---

#### `report/JsonReporter.java`

Writes machine-readable JSON to a file path.

```json
{
  "suite": "Order E2E",
  "startedAt": "2024-01-01T00:00:00Z",
  "steps": [
    {
      "name": "Seed users",
      "status": "PASSED",
      "durationMs": 45,
      "detail": "...",
      "assertions": ["Row count == 1"]
    }
  ],
  "passed": true,
  "totalMs": 1234,
  "finishedAt": "2024-01-01T00:00:01.234Z"
}
```

Uses Jackson's `ObjectMapper` with pretty-print. Creates parent directories if they don't exist.

---

#### `junit/TestKitExtension.java`

JUnit 5 `ParameterResolver` + `AfterEachCallback`.

- Registers under `ExtensionContext.Namespace.create(TestKitExtension.class)` to avoid namespace collisions
- On `resolveParameter`: returns a `TestKitContext` stored per-test in the JUnit `ExtensionContext.Store`
- `supportsParameter`: true only when parameter type is `TestKitContext.class`
- Companion annotation `@TestKitTest`: `@ExtendWith(TestKitExtension.class)` on the class level

```java
@TestKitTest
class OrderTests {
    @Test
    void placeOrder(TestKitContext ctx) {
        // ctx is a fresh, test-scoped TestKitContext
    }
}
```

---

### 3.2 testkit-seed

**Package:** `io.testkit.seed`  
**Purpose:** Database test data seeding — factory-generated, fixture-loaded, or raw SQL.

---

#### `FieldSupplier<T>.java`

Functional interface extending `java.util.function.Supplier<T>`. Provides lazy evaluation — the supplier is called once per `buildMap()` invocation, so each row gets fresh values.

Static factories (all create a `Faker` instance per call, lazy):

| Method | Returns | Backed by |
|---|---|---|
| `fixed(T value)` | `T` | constant |
| `uuid()` | `String` | `UUID.randomUUID()` |
| `email()` | `String` | `Faker.internet().emailAddress()` |
| `name()` | `String` | `Faker.name().fullName()` |
| `firstName()` | `String` | `Faker.name().firstName()` |
| `lastName()` | `String` | `Faker.name().lastName()` |
| `phone()` | `String` | `Faker.phoneNumber().cellPhone()` |
| `password()` | `String` | `Faker.internet().password(12,20,true,true,true)` |
| `sequence()` | `Long` | Closure over `long[] counter = {0}` |
| `lorem(int words)` | `String` | `Faker.lorem().words(n)` joined with spaces |
| `address()` | `String` | `Faker.address().fullAddress()` |
| `companyName()` | `String` | `Faker.company().name()` |

---

#### `DataFactory<T>.java`

Abstract base class for test data factories.

```
State:
  Map<String, FieldSupplier<?>> fields  (LinkedHashMap — preserves insertion order)
  boolean defined                        (lazy-init guard)

Abstract method:
  define()  ← subclasses call field(name, supplier) here

Final methods:
  buildMap()                   → Map<String,Object>  (calls define() once, then get() each supplier)
  buildMaps(int count)         → List<Map<String,Object>>
  buildMap(Map overrides)      → Map<String,Object>  (merge overrides over base)
  build()                      → T  (casts buildMap() — override for typed result)
  build(int count)             → List<T>
```

Example subclass:
```java
public class UserFactory extends DataFactory<Map<String, Object>> {
    @Override
    protected void define() {
        field("id",       FieldSupplier.uuid());
        field("email",    FieldSupplier.email());
        field("name",     FieldSupplier.name());
        field("created",  FieldSupplier.fixed(LocalDate.now()));
    }
}
```

---

#### `SeedBuilder.java`

Fluent DSL. Holds a `List<SeedOperation>` (private `@FunctionalInterface` accepting `TestKitContext`).

```
Methods:
  dataSource(DataSource)               → SeedBuilder
  table(String tableName)              → FactoryStep   (inner class)
  sql(String sql, Object... params)    → SeedBuilder   (raw SQL execution)
  truncate(String... tables)           → SeedBuilder   (TRUNCATE TABLE for each)

Execution:
  executeSeed(TestKitContext ctx)      → runs all queued SeedOperations in order

Inner class FactoryStep:
  fromFactory(DataFactory, int count)  → SeedBuilder
    - Generates rows via factory.buildMaps(count)
    - Inserts via JdbcSeeder.insertAll(table, rows)
    - ctx.put("seed.<table>.keys", keys)
    - ctx.put("seed.<table>.rows", rows)
  fromRows(List<Map<String,Object>>)   → SeedBuilder
    - Inserts literal rows
  fromFixture(String classpathResource)→ SeedBuilder
    - Loads via FixtureLoader.from(resource)
    - Inserts loaded rows
```

---

#### `JdbcSeeder.java`

Thin JDBC wrapper using `HikariCP` connection pooling.

```
Methods:
  insertAll(table, rows) → List<Map<String,Object>>  (generated keys per row)
  execute(sql, params)   → void
  truncate(table)        → void
  exists(table, where, params) → boolean
  count(table, where, params)  → long
```

Builds INSERT statements dynamically from the map's key set. Uses `PreparedStatement.RETURN_GENERATED_KEYS` to capture auto-increment / sequence values.

---

#### `FixtureLoader.java`

Loads test fixtures from classpath resources.

- `.json` files: parsed by Jackson into `List<Map<String,Object>>`
- `.yaml` / `.yml` files: parsed by SnakeYAML into `List<Map<String,Object>>`
- Throws `TestKitException` if file not found or format unrecognised

---

#### `SeedStep.java`

Implements `TestKitStep`. Calls `SeedBuilder.executeSeed(ctx)` inside `execute()`. Returns `StepResult.passed(name, duration)` on success; any exception is caught by `TestKitRunner`.

---

### 3.3 testkit-api

**Package:** `io.testkit.api`  
**Purpose:** HTTP request-response testing — send requests, assert responses, extract values to context.

---

#### `HttpMethod.java`

```java
enum HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }
```

---

#### `ApiConfig.java`

Immutable per-step HTTP configuration.

```
Fields:
  String baseUrl        (default: "http://localhost:8080")
  Duration timeout      (default: 10 seconds)
  boolean followRedirects (default: true)
  boolean verifySsl     (default: true)
  Map<String,String> defaultHeaders

Static factory: ApiConfig.defaults()
Builder: ApiConfig.builder()
```

---

#### `RequestSpec.java`

Immutable HTTP request specification (built, then passed to `HttpTestClient`).

```
Fields:
  HttpMethod method
  String url           (fully resolved — base + path + path-params substituted)
  Map<String,String> headers
  Map<String,String> queryParams
  String jsonBody      (nullable)
  byte[] rawBody       (nullable)
  Map<String,String> formParams
  ApiConfig config
```

`RequestSpec.Builder` handles URL construction:
1. Resolves `{param}` placeholders in path string against `pathParams` map
2. Appends query params as `?k=v&k2=v2`
3. Produces the final URL string

---

#### `HttpTestClient.java`

OkHttp 4 wrapper. Stateless — creates a new `OkHttpClient` per instance.

```
Constructor: HttpTestClient(ApiConfig config)
  Configures OkHttpClient with:
    - connectTimeout / readTimeout from config
    - followRedirects from config
    - SSL verification bypass if !config.verifySsl()

execute(RequestSpec spec) → ApiResponse:
  Builds okhttp3.Request from spec
  Sets Content-Type: application/json for json bodies
  Calls client.newCall(request).execute()
  Wraps response in ApiResponse
  Throws TestKitException on IOException
```

---

#### `ApiResponse.java`

Immutable response wrapper.

```
Fields:
  int statusCode
  String body          (read eagerly, OkHttp body closed)
  Map<String,String> headers
  long durationMs

Methods:
  assertStatus(int expected)           → throws TestKitException if mismatch
  assertBodyContains(String substring) → throws TestKitException if not found
  assertJsonPath(String path, Object expected) → JSONPath evaluation via Jayway
  assertJsonPathPresent(String path)   → asserts path resolves to non-null
  assertHeader(String name, String expected)
  assertContentType(String expected)
  jsonPath(String path)                → Object   (raw JSONPath result)
  jsonPathString(String path)          → String   (toString of JSONPath result)
  body()                               → String
  status()                             → int
  header(String name)                  → String (nullable)
```

---

#### `ApiBuilder.java`

Central fluent DSL for a single request-response cycle.

```
State fields (all mutable during build phase):
  HttpMethod method
  String path
  String baseUrl             (overrides config baseUrl for this step)
  Map<String,String> headers
  Map<String,Object> pathParams  (Object to allow String literals OR Function<ctx,String>)
  Map<String,String> queryParams
  Object jsonBody            (String literal, POJO, or Function<ctx,String>)
  byte[] rawBody
  Map<String,String> formParams
  String bearerToken
  int expectedStatus         (-1 means no status assertion)
  List<Consumer<ApiResponse>> assertions
  Map<String,String> extractions  (contextKey → jsonPath)
  Consumer<ApiResponse> onResponse
  ApiConfig config

Build methods (return this):
  GET/POST/PUT/PATCH/DELETE(path)
  baseUrl(String)
  header(name, value)
  bearerToken(token)
  pathParam(name, String)            static param
  pathParam(name, Function<ctx,S>)   dynamic param — resolved at executeWith() time
  query(name, value)
  formParam(name, value)
  json(String)  / json(Object pojo) / json(Function<ctx,String>)
  rawBody(byte[])
  config(ApiConfig)
  expect(int status)
  assertBody(substring)
  assertJsonPath(path, expected)
  assertJsonPathPresent(path)
  assertHeader(name, expected)
  assertContentType(expected)
  assertThat(Consumer<ApiResponse>)  custom assertion
  extract(contextKey, jsonPath)
  onResponse(Consumer<ApiResponse>)

Execution (package-accessible):
  executeWith(TestKitContext ctx, ApiConfig defaultConfig) → ApiResponse
    1. Build effective ApiConfig (step-level overrides suite-level)
    2. Create HttpTestClient
    3. Resolve all path params (ctx.resolve on each value)
    4. Resolve json body (ctx.resolve)
    5. Execute HTTP request
    6. Assert status if expectedStatus >= 0
    7. Run all Consumer<ApiResponse> assertions
    8. For each extraction: ctx.put(key, response.jsonPathString(jsonPath))
    9. Call onResponse callback if set
    10. Return ApiResponse
```

---

#### `ApiStep.java`

Implements `TestKitStep`. Wraps `ApiBuilder.executeWith()`. Captures the start/end `Instant` to report duration. Returns `StepResult.passed(name, duration)` with the response status as detail.

---

#### `TestKitApiExtensions.java`

Static utility class providing `.api(Consumer<ApiBuilder>)` registration on a `TestKit` instance. This is how `TestKit.step(new ApiStep(...))` is abstracted away from `TestKitDsl`.

---

### 3.4 testkit-load

**Package:** `io.testkit.load`  
**Purpose:** Load and performance testing using Java 21 virtual threads with HDR histogram metrics.

---

#### `LoadConfig.java`

```
Fields:
  int virtualUsers       (default: 10)
  int targetRps          (default: 0 = unlimited)
  Duration rampUp        (default: ZERO)
  Duration duration      (default: 30s)
  Duration coolDown      (default: 5s)

Builder: LoadConfig.builder()
```

---

#### `LoadScenario.java`

Defines the repeatable user journey executed by each virtual user.

```
Interface:
  newIteration() → Runnable   (called once per VU, returns the iteration body)

Static factory:
  LoadScenario.http(name, Consumer<ApiBuilder>, ApiConfig) → LoadScenario
    Returns a LoadScenario whose newIteration() creates a fresh ApiBuilder,
    applies the Consumer spec, then calls executeWith(new TestKitContext(), config)
    NOTE: each VU iteration uses a throw-away TestKitContext — no state sharing between VUs
```

Custom scenarios implement `LoadScenario` directly for multi-step or non-HTTP journeys.

---

#### `LoadRunner.java`

Core load engine.

```
State:
  LoadConfig config
  LoadScenario scenario

run() algorithm:
  metrics = new LoadMetrics()
  running = AtomicBoolean(true)
  rateLimiter = config.targetRps > 0 ? new RateLimiter(rps) : null

  try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()):
    metrics.recordStart()

    if rampUp > 0: Thread.sleep(rampUp)  ← intentional blocking of runner thread

    for vu in 0..virtualUsers:
      iteration = scenario.newIteration()
      executor.submit(VU loop):
        while running.get():
          if rateLimiter != null: rateLimiter.acquire()  ← blocks if over-rate
          start = System.currentTimeMillis()
          success = true
          try:
            iteration.run()
          catch TestKitException | AssertionError:
            success = false
          catch Exception:
            success = false
          duration = now - start
          metrics.record(duration, success)

    Thread.sleep(config.duration)    ← sustain load
    running.set(false)               ← signal all VUs to stop

    for each vuFuture: f.get(10s, TIMEOUT)  ← drain

    if coolDown > 0: Thread.sleep(coolDown)
    metrics.recordEnd()
  
  return metrics
```

**Inner class `RateLimiter` (token bucket):**

```
State:
  long intervalNs   = 1_000_000_000 / rps   (nanoseconds between permits)
  long nextPermitNs = System.nanoTime()

synchronized acquire():
  now = System.nanoTime()
  wait = nextPermitNs - now
  if wait > 0:
    Thread.sleep(wait / 1_000_000, (int)(wait % 1_000_000))
  nextPermitNs = max(System.nanoTime(), nextPermitNs) + intervalNs
```

This is a classic token bucket. The `max()` prevents permit times from drifting arbitrarily into the past if the system is under-loaded (i.e., if requests come in slower than the rate allows).

---

#### `LoadMetrics.java`

Thread-safe metrics. All latency tracking uses HdrHistogram.

```
State:
  Histogram latencyHistogram    (HDR, 1µs–60s range, 3 significant figures)
  LongAdder totalRequests
  LongAdder successCount
  LongAdder errorCount
  volatile long startEpochMs
  volatile long endEpochMs

record(durationMs, success):
  synchronized(latencyHistogram): latencyHistogram.recordValue(max(durationMs, 1))
  totalRequests.increment()
  if success: successCount.increment()
  else:        errorCount.increment()

Percentile methods (all synchronized on histogram):
  p50() / p95() / p99() / p999() / max() / mean() / min()

Throughput:
  totalRequests() / successCount() / errorCount()
  errorRatePercent() = (errors / total) * 100, or 0 if total == 0
  actualRps()        = total * 1000.0 / durationMs, or 0 if durationMs <= 0
  durationMs()       = endEpochMs - startEpochMs
```

**Why HDR Histogram?** Standard `ArrayList<long>` or `synchronized Map<Long,Long>` approaches require O(N) memory or lock contention under high concurrency. HDR Histogram uses a fixed-size pre-allocated bucket array. `recordValue()` is O(1) with minimal GC pressure, and percentile queries are O(log N) through the bucket range.

---

#### `Threshold.java`

Abstract base class for SLO checks.

```
Abstract: check(LoadMetrics) → void (throws TestKitException on violation)
Field: String description

Concrete implementations via static factories:
  Threshold.p50Under(maxMs)
  Threshold.p95Under(maxMs)
  Threshold.p99Under(maxMs)
  Threshold.maxUnder(maxMs)
  Threshold.errorRateUnder(maxPct)

Inner abstract class LatencyThreshold:
  Requires subclasses to provide:
    value(LoadMetrics) → long    (which percentile to read)
    label() → String             (human label for error message)

Static utility:
  Threshold.checkAll(List<Threshold>, LoadMetrics):
    Runs ALL thresholds, collects ALL failures, then throws one combined exception
    (never short-circuits — you see all failures at once)
```

---

#### `LoadReport.java`

Pretty-print output for a completed load run.

```
Printed to stdout:
  ┌─────────────────────────────────────┐
  │  Load Test: <scenarioName>          │
  │  VUs: N  |  Duration: Xs  |  RPS target: M │
  ├─────────────────────────────────────┤
  │  Total requests: NNNN               │
  │  Success: NNNN  |  Errors: N (X.X%) │
  │  Actual RPS: X.X                    │
  │  Latency  P50: Xms  P95: Xms        │
  │           P99: Xms  Max: Xms        │
  └─────────────────────────────────────┘
```

---

#### `LoadBuilder.java`

Fluent DSL for load test configuration.

```
State:
  String scenarioName   (default: "Load Test")
  int virtualUsers      (default: 10)
  int targetRps         (default: 0)
  Duration rampUp       (default: ZERO)
  Duration duration     (default: 30s)
  Duration coolDown     (default: 5s)
  List<Threshold> thresholds
  Consumer<ApiBuilder> apiSpec    (HTTP shorthand)
  ApiConfig apiConfig
  LoadScenario customScenario

HTTP shorthand methods (GET/POST/PUT/DELETE/PATCH/json/header/expect):
  Each wraps current apiSpec by composing: prev.accept(a); newOp.accept(a)
  This allows: .POST("/endpoint").json("{...}").expect(200) in load context

Load shape: virtualUsers/rps/rampUp/duration(long seconds)/coolDown

SLO shortcuts: p99Under/p95Under/p50Under/maxUnder/errorRateUnder
  All delegate to threshold(Threshold.xxxUnder(value))

executeLoad(ctx) → LoadResult(metrics, report):
  1. buildScenario(ctx): uses customScenario OR LoadScenario.http(name, apiSpec, effective)
  2. Build LoadConfig from fields
  3. LoadRunner.run() → metrics
  4. Threshold.checkAll(thresholds, metrics) if thresholds non-empty
  5. new LoadReport(scenarioName, config, metrics).print()
  6. return new LoadResult(metrics, report)
```

---

### 3.5 testkit-db

**Package:** `io.testkit.db`  
**Purpose:** Database state assertions after operations complete.

---

#### `DbClient.java`

Thin JDBC wrapper that returns `RowSet` objects.

```
Methods:
  query(sql, params...) → RowSet
  exists(table, where, params...) → boolean
  count(table, where, params...) → long
  execute(sql, params...) → void
```

Obtains connections from the provided `DataSource` (typically `HikariCP`). Uses `PreparedStatement` with positional `?` parameters throughout.

---

#### `RowSet.java`

Immutable query result wrapper.

```
State:
  List<Map<String,Object>> rows   (column name → value, using ResultSetMetaData for names)

Methods:
  assertRowCount(int expected)              throws if rows.size() != expected
  assertNotEmpty()                          throws if rows is empty
  assertEmpty()                             throws if rows is non-empty
  assertColumn(String col, Object expected) checks rows.get(0).get(col).equals(expected)
  assertColumnNull(String col)              checks rows.get(0).get(col) == null
  assertColumnNotNull(String col)           checks rows.get(0).get(col) != null
  rows()                                    → List<Map<String,Object>>
  first()                                   → Map<String,Object> (throws if empty)
  size()                                    → int
```

---

#### `DbBuilder.java`

Fluent DSL for DB assertions. Operations accumulate in `List<DbOperation>`.

```
Top-level methods (return DbBuilder):
  dataSource(DataSource)
  assertExists(table, where, params...)
  assertNotExists(table, where, params...)
  assertCount(table, expected)
  assertCount(table, where, expected, params...)
  execute(sql, params...)                 raw SQL (cleanup, state setup)
  extract(contextKey, sql, params...)     scalar extraction into context

Inner class QueryBuilder (returned by query(sql, params)):
  assertRowCount(int)
  assertNotEmpty()
  assertEmpty()
  assertColumn(col, expected)
  assertColumnNull(col)
  assertColumnNotNull(col)
  end() → DbBuilder               terminates inner builder, registers operation, returns outer

Execution:
  executeDb(ctx): runs all operations in order
```

`end()` is a "terminator" pattern — `QueryBuilder` methods are only meaningful if `.end()` is eventually called to commit the assertion block back to the parent `DbBuilder`. This is a deliberate API choice to make the inner query scope visually distinct.

---

### 3.6 testkit-contract

**Package:** `io.testkit.contract`  
**Purpose:** Consumer-driven contract testing — write contracts as JSON, verify provider satisfies them.

---

#### `Contract.java`

JSON-serializable contract definition.

```java
record Contract(
    String consumer,
    String provider,
    String description,
    ContractRequest request,    // nullable
    ContractResponse response   // nullable
) {
    record ContractRequest(String method, String path, Map<String,String> headers, String body) {}
    record ContractResponse(int status, Map<String,String> headers, String body, Map<String,Object> bodyMatchers) {}

    void writeTo(Path path)        // Jackson serialisation, creates parent dirs
    static Contract readFrom(Path) // Jackson deserialisation
}
```

---

#### `ContractBuilder.java`

Fluent DSL for both consumer-side definition and provider-side verification.

**Consumer flow (`.saveContractTo()` is called):**

```
1. verifyConsumerContract(ctx):
   a. Apply expectedStatus and bodyMatchers to requestBuilder
   b. executeWith(ctx, effective) → sends real HTTP request to running service
   c. If assertions pass → contract is "verified against the consumer's service"
   d. If contractSavePath set → serialize Contract to JSON
```

**Provider flow (`.verifyContract(path)` is called):**

```
1. verifyProviderContract(ctx):
   a. Contract.readFrom(contractLoadPath) → load saved contract
   b. Apply contract.response.status + bodyMatchers to the given requestBuilder
   c. executeWith(ctx, effective) → sends request to provider
   d. If assertions pass → provider satisfies the contract
```

```
Builder methods:
  consumer(String) / provider(String) / describe(String)
  request(ApiBuilder)          the HTTP request to send
  expectStatus(int)
  expectBodyField(String jsonPath)          "field must be present"
  expectBodyField(String jsonPath, Object)  "field must equal value"
  apiConfig(ApiConfig)
  saveContractTo(Path)
  verifyContract(Path)         switches to provider verification mode
```

---

### 3.7 testkit-security

**Package:** `io.testkit.security`  
**Purpose:** Automated OWASP-inspired security scanning integrated into the test pipeline.

---

#### `SecurityFinding.java`

```java
record SecurityFinding(
    String probeName,
    Severity severity,
    String description,
    String targetPath,
    String payload,
    String evidence
) {
    enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
}
```

Severity ordering: `CRITICAL.ordinal()=0 < HIGH.ordinal()=1 < ... < INFO.ordinal()=4`

---

#### `SecurityProbe.java`

SPI interface.

```java
interface SecurityProbe {
    String name();
    List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig);
}
```

---

#### Built-in Probes

**`SqlInjectionProbe`**

11 payloads fired as GET query parameter `?id=<payload>`:

```
'
' OR '1'='1
' OR '1'='1' --
" OR "1"="1
1; DROP TABLE users; --
1' AND SLEEP(0) --
1 UNION SELECT NULL --
' OR 1=1 LIMIT 1 --
admin'--
1' ORDER BY 1--+
' AND 1=CONVERT(int,(SELECT TOP 1 table_name FROM information_schema.tables)) --
```

14 error signatures scanned in response body (lowercased):
`sql syntax`, `mysql_fetch`, `ora-`, `pg_query`, `sqlite_`, `syntax error`, `unclosed quotation`, `sqlexception`, `sqlstate`, `jdbctemplate`, `hibernate`, `jpa`, `column`, `database error`

Verdict: `HIGH` finding if HTTP 400 or 500 response AND body contains any error signature.

---

**`XssProbe`**

Fires reflected XSS payloads as query parameters. Checks if the payload appears verbatim in the response body (indicating reflection without sanitisation).

Sample payloads:
```
<script>alert('xss')</script>
"><script>alert(1)</script>
<img src=x onerror=alert(1)>
javascript:alert(1)
```

Verdict: `HIGH` finding if response body contains the raw payload string.

---

**`AuthBypassProbe`**

Sends 4 requests to a protected endpoint:

| Scenario | Authorization Header |
|---|---|
| No auth header | *(absent)* |
| Invalid bearer | `Bearer invalid.token.here` |
| Empty bearer | `Bearer ` |
| JWT alg:none attack | `Bearer eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJhZG1pbiJ9.` |

Verdict: `CRITICAL` if ANY of these receives HTTP 200 or 201. A 401 or 403 is the expected (correct) response.

The JWT alg:none payload decodes to:
```json
{ "alg": "none", "typ": "JWT" }.{ "sub": "admin" }.(empty signature)
```

This tests whether the server rejects unsigned JWTs (CVE-class vulnerability in many JWT libraries).

---

**`RateLimitProbe`**

Sends 50 rapid sequential GET requests to the target endpoint. Checks whether any 429 (Too Many Requests) response is received.

Verdict: `MEDIUM` finding if no 429 was returned across 50 requests (endpoint may lack rate limiting).

---

**`SensitiveDataExposureProbe`**

Performs a GET to the endpoint and regex-scans the response body for:

| Pattern | Detects |
|---|---|
| `\b4[0-9]{12}(?:[0-9]{3})?\b` | Visa card number |
| `\b5[1-5][0-9]{14}\b` | MasterCard number |
| `\b3[47][0-9]{13}\b` | Amex card number |
| `\b\d{3}-\d{2}-\d{4}\b` | US SSN |
| `AKIA[0-9A-Z]{16}` | AWS Access Key ID |
| `-----BEGIN (?:RSA )?PRIVATE KEY-----` | PEM private key |
| `"password"\s*:\s*"[^"]+"` | Plaintext password field in JSON |
| `at [a-zA-Z]+\.[a-zA-Z]+\(` | Java stack trace |

Verdict: `HIGH` for card/SSN/AWS key/private key. `MEDIUM` for password field / stack trace.

---

#### `SecurityBuilder.java`

```
State:
  ApiConfig apiConfig            (global for all probes in this step)
  List<ScanTarget> scanTargets   (record: endpoint + SecurityProbe)
  SecurityFinding.Severity failOnSeverity   (default: HIGH)
  boolean failOnAnyFinding       (default: false)

Builder methods:
  baseUrl(String) / apiConfig(ApiConfig)
  failOnSeverity(Severity)
  failOnAnyFinding()
  sqlInjection(endpoint) / xss(endpoint) / authBypass(endpoint)
  rateLimit(endpoint) / sensitiveDataExposure(endpoint)
  fullScan(endpoint)             → registers sqlInjection + xss + sensitiveDataExposure + rateLimit
  probe(endpoint, SecurityProbe) custom probe

executeSecurity(ctx):
  1. For each ScanTarget: probe.probe(endpoint, apiConfig) → List<SecurityFinding>
  2. Merge all findings
  3. SecurityReport.print() → console output
  4. if failOnAnyFinding && findings non-empty → throw
  5. Filter findings by severity <= failOnSeverity
  6. if criticalFindings non-empty → throw with all findings listed
  7. return SecurityReport
```

---

### 3.8 testkit-mock

**Package:** `io.testkit.mock`  
**Purpose:** Embedded WireMock stub server lifecycle management.

---

#### `MockBuilder.java`

```
State:
  int port              (0 = dynamic/random)
  List<Consumer<StubBuilder>> stubConfigs
  String contextKey     (if set, mock base URL stored in context under this key)

startServer(TestKitContext ctx) → ManagedMockServer:
  1. WireMockServer configured with explicit port or dynamic port
  2. server.start()
  3. WireMock.configureFor("localhost", server.port())   ← sets default WireMock client
  4. For each stubConfig:
     sb = new StubBuilder(server)
     cfg.accept(sb)
     sb.register()         ← calls server.stubFor(mappingBuilder.willReturn(response))
  5. if contextKey set: ctx.put(contextKey, "http://localhost:" + server.port())
  6. return new ManagedMockServer(server, baseUrl)
```

**Inner class `StubBuilder`:**

```
State:
  String method            (GET / POST / PUT / DELETE / PATCH)
  String urlPattern        (exact URL match)
  String bodyContains      (optional request body substring match)
  String contentType       (default: application/json)
  int statusCode           (default: 200)
  String responseBody      (default: "{}")
  long delayMs             (default: 0)

Methods:
  GET/POST/PUT/DELETE/PATCH(url)
  withBody(String)         optional request body filter
  contentType(String)
  delay(long ms)           artificial response delay (simulates slow services)
  willReturn(status, body)
  willReturn(status)

register():
  Builds WireMock MappingBuilder:
    - URL: urlEqualTo(urlPattern)
    - Body filter: containing(bodyContains) if set
    - Response: aResponse().withStatus().withHeader("Content-Type",...).withBody()
    - Delay: .withFixedDelay(ms) if delayMs > 0
  Calls server.stubFor(...)
```

---

#### `ManagedMockServer.java`

```java
record ManagedMockServer(WireMockServer server, String baseUrl) implements AutoCloseable {
    public void close() { if (server.isRunning()) server.stop(); }
    public int port() { return server.port(); }
    public void verify(int times, RequestPatternBuilder pattern) { server.verify(times, pattern); }
}
```

`AutoCloseable` enables try-with-resources. The `MockStep` implementation currently starts the server before the API steps and leaves it running for the duration of the suite (handled at step level, not try-with-resources).

---

#### `MockStep.java`

Implements `TestKitStep`. Calls `MockBuilder.startServer(ctx)` and stores the `ManagedMockServer` reference. Shuts down the server at `StepResult` time. (The server is up for subsequent steps via the context key.)

---

### 3.9 testkit-queue

**Package:** `io.testkit.queue`  
**Purpose:** Produce and consume messages on Kafka and RabbitMQ with assertion helpers.

---

#### `QueueMessage.java`

```java
record QueueMessage(
    String topic,
    String key,
    String value,
    Map<String, String> headers,
    long offset,
    int partition
)
```

---

#### `KafkaTestClient.java` (`AutoCloseable`)

```
State:
  String bootstrapServers
  KafkaProducer<String,String> producer   (lazy-created, reused)

produce(topic, value):
  produce(topic, null, value, emptyMap())

produce(topic, key, value):
  produce(topic, key, value, emptyMap())

produce(topic, key, value, headers):
  getOrCreateProducer()
  ProducerRecord with headers (UTF-8 byte encoding per header)
  producer.send(record).get(10, SECONDS)   ← blocks for confirmation

Producer config:
  bootstrap.servers = bootstrapServers
  acks = 1           (leader ack, balanced durability/throughput)
  serializers: StringSerializer for key and value

consume(topic, maxMessages, timeout):
  New KafkaConsumer per call (never cached — each consume is a fresh context)
  group.id = "testkit-consumer-" + UUID.randomUUID()   ← random group prevents cross-test interference
  auto.offset.reset = earliest                          ← start from beginning of topic
  enable.auto.commit = false                            ← no accidental offset advancement
  consumer.subscribe([topic])
  poll loop until maxMessages collected or deadline reached
  Returns List<QueueMessage>

consumeOne(topic, timeout):
  consume(topic, 1, timeout)
  Throws TestKitException if list empty

assertMessageOnTopic(topic, bodyContains, timeout):
  msg = consumeOne(topic, timeout)
  if !msg.value().contains(bodyContains): throw
```

**Why random group ID?** If two tests share a group ID, they share offsets. Test A consuming messages would advance the offset, causing Test B to miss messages. With UUID group IDs and `earliest` reset, every test run starts from offset 0 on every topic subscription, guaranteeing test isolation.

---

#### `RabbitTestClient.java`

```
Connection management:
  ConnectionFactory with host, virtual host "/", default port 5672
  Single Connection + Channel per client instance (created lazily)

publishToQueue(queue, message):
  channel.basicPublish("", queue, MessageProperties.PERSISTENT_TEXT_PLAIN, message.getBytes(UTF_8))

publish(exchange, routingKey, message):
  channel.basicPublish(exchange, routingKey, MessageProperties.PERSISTENT_TEXT_PLAIN, message.getBytes(UTF_8))

assertMessageOnQueue(queue, bodyContains, timeout):
  Poll with channel.basicGet(queue, true) in a loop until timeout
  Thread.sleep(200ms) between polls
  Throws if no matching message found within timeout

purgeQueue(queue):
  channel.queuePurge(queue)

close():
  channel.close() + connection.close() (both null-safe)
```

---

#### `QueueBuilder.java`

```
State:
  String kafkaBootstrapServers   (null until .kafka() called)
  String rabbitHost              (null until .rabbit() called)
  List<QueueOperation> operations

Kafka methods:
  kafka(bootstrapServers)
  produce(topic, value)
  produce(topic, key, value)
  assertMessageOnTopic(topic, bodyContains, timeout)
  consumeToContext(contextKey, topic, timeout)   ← stores QueueMessage in context

Rabbit methods:
  rabbit(host)
  publishToQueue(queue, message)
  publishToExchange(exchange, routingKey, message)
  assertMessageOnQueue(queue, bodyContains, timeout)
  purgeQueue(queue)

executeQueue(ctx):
  for op in operations: op.execute(ctx)

kafkaClient() → new KafkaTestClient(bootstrapServers)  (created per operation call)
rabbitClient() → new RabbitTestClient(rabbitHost)
```

---

### 3.10 testkit-spring

**Package:** `io.testkit.spring`  
**Purpose:** Spring Boot 3 autoconfiguration for zero-config TestKit integration.

---

#### `TestKitProperties.java`

```java
@ConfigurationProperties(prefix = "testkit")
public final class TestKitProperties {
    private String baseUrl   = "http://localhost:8080";
    private Duration timeout = Duration.ofSeconds(10);
    private boolean failFast = true;
    private boolean verbose  = false;
    // standard getters/setters
}
```

Maps `application.yml`:
```yaml
testkit:
  base-url: http://localhost:${local.server.port}
  timeout: 15s
  fail-fast: true
  verbose: false
```

---

#### `TestKitAutoConfiguration.java`

```java
@AutoConfiguration
@EnableConfigurationProperties(TestKitProperties.class)
public class TestKitAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public TestKitConfig testKitConfig(TestKitProperties props) {
        return TestKitConfig.builder()
            .baseUrl(props.getBaseUrl())
            .timeout(props.getTimeout())
            .failFast(props.isFailFast())
            .verbose(props.isVerbose())
            .build();
    }

    @Bean @ConditionalOnMissingBean
    public TestKitFactory testKitFactory(TestKitConfig config) {
        return new TestKitFactory(config);
    }
}
```

Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 3 mechanism, replacing the legacy `spring.factories`).

`@ConditionalOnMissingBean` on both beans allows users to override either the config or the factory by declaring their own `@Bean`.

---

#### `TestKitFactory.java`

```java
public final class TestKitFactory {
    private final TestKitConfig config;

    public TestKitFactory(TestKitConfig config) { this.config = config; }

    public TestKit test(String suiteName) {
        return TestKit.test(suiteName).config(config);
    }
}
```

Used in Spring Boot `@SpringBootTest` classes:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
class OrderApiTest {

    @Autowired TestKitFactory testKit;

    @Test
    void createOrder() {
        testKit.test("Create Order")
            .api(a -> a.POST("/api/orders").json("{...}").expect(201))
            .runAndAssert();
    }
}
```

The `TestKitFactory` pre-wires the `baseUrl` from the running test server's port, so tests don't need to know the port.

---

## 4. Key Algorithms and Design Decisions

### 4.1 Late-Binding Context Resolution

Every builder that accepts user-supplied values (path params, JSON bodies, query params) stores them as `Object` and resolves them at execution time via `ctx.resolve(valueOrFn)`:

```java
public <T> T resolve(Object valueOrFn) {
    if (valueOrFn instanceof Function<?, ?> fn) {
        return ((Function<TestKitContext, T>) fn).apply(this);
    }
    return (T) valueOrFn;
}
```

This means you can write:

```java
.api(a -> a.GET("/orders/{id}").pathParam("id", ctx -> ctx.get("orderId")))
```

The lambda is stored in the builder at test-definition time. When `execute()` runs, the lambda is called with the live context, which by that point contains `orderId` from the previous API step's `.extract("orderId", "$.id")`. This is the mechanism that makes step chaining without explicit variables possible.

---

### 4.2 Virtual Thread Load Testing

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int vu = 0; vu < config.virtualUsers(); vu++) {
        executor.submit(() -> {
            while (running.get()) {
                // iterate
            }
        });
    }
}
```

Java 21 virtual threads are `Thread` objects that are multiplexed onto a much smaller pool of OS threads by the JVM scheduler. They have sub-microsecond creation cost and ~1KB stack by default (vs ~1MB for platform threads). This means running 10,000 virtual users is practical on a laptop — something impossible with platform threads.

The `try-with-resources` on `ExecutorService` uses the new `AutoCloseable` semantic added in Java 19+: it calls `executor.shutdown()` and `awaitTermination()` automatically.

---

### 4.3 HDR Histogram for Latency

Standard `ArrayList.sort()` for percentiles requires O(N log N) per query and O(N) memory. `synchronized HashMap<Long, Long>` as a frequency map has contention issues.

HDR Histogram pre-allocates a fixed-size array of buckets covering the configured value range (1µs to 60s here) with a configurable precision (3 significant figures). `recordValue()` is:

```
bucket_index = log2(value) * precision_factor
histogram[bucket_index]++
```

This is O(1) per record and O(log N) per percentile query (traversing bucket boundaries). Memory footprint is fixed regardless of the number of samples — typically ~40KB for the full 60s range at 3 significant figures.

---

### 4.4 Token Bucket Rate Limiter

The `RateLimiter` inside `LoadRunner` uses a standard token-bucket algorithm implemented in nanosecond precision:

```
intervalNs = 1,000,000,000 / targetRps

On acquire():
  wait = nextPermitNs - currentNs
  if wait > 0: sleep(wait)
  nextPermitNs = max(currentNs, nextPermitNs) + intervalNs
```

The `max()` is critical: without it, if requests come in slower than the rate (e.g., the server is slow), `nextPermitNs` would drift further and further into the past. Future requests would then find `wait < 0` for a long time and fire a burst. The `max()` clamps `nextPermitNs` to never be more than one interval ahead.

Note the `synchronized` on `acquire()` — all VUs share one rate limiter, so the aggregate RPS across all VUs is limited to `targetRps`, not `targetRps × VUs`.

---

### 4.5 Consumer-Driven Contract Testing

The contract testing model follows the Pact philosophy:

1. **Consumer writes the contract.** The consumer service (e.g., `order-service`) defines what it expects from the provider (`payment-service`) in terms of request structure and response shape.
2. **Contract is serialised to JSON.** The `Contract` record is written to a file (typically committed to a shared contracts repository or artifact store).
3. **Provider verifies the contract.** The provider runs a test that loads the JSON contract and sends the defined request to its own running instance, asserting the response matches the contract.

This decouples consumer and provider teams — the consumer defines requirements without coordinating with the provider directly.

---

### 4.6 WireMock Dynamic Port

```java
WireMockServer server = new WireMockServer(
    port > 0
        ? WireMockConfiguration.wireMockConfig().port(port)
        : WireMockConfiguration.wireMockConfig().dynamicPort());
```

Dynamic port (port=0) avoids port conflicts in CI environments where multiple tests run in parallel. The actual port is obtained via `server.port()` after start, and stored in context via `contextKey` for subsequent steps to use:

```java
ctx.put("mockBaseUrl", "http://localhost:" + server.port());
```

Subsequent API steps can then use:
```java
.api(a -> a.baseUrl(ctx -> ctx.get("mockBaseUrl")).GET("/api/products"))
```

---

## 5. Dependency Manifest

| Artifact | Version | Used by | Purpose |
|---|---|---|---|
| `junit-jupiter-api` | 5.10.2 | testkit-core | `TestKitExtension`, `@TestKitTest` |
| `junit-jupiter-engine` | 5.10.2 | testkit-core (test) | Run ExampleTest |
| `jackson-databind` | 2.17.0 | testkit-core, testkit-contract | JSON serialisation (reports, contracts) |
| `jackson-datatype-jsr310` | 2.17.0 | testkit-contract | Java time type support |
| `slf4j-api` | 2.0.13 | testkit-core + all | Logging facade |
| `logback-classic` | 1.5.3 | testkit-core (runtime) | Logging implementation |
| `commons-lang3` | 3.14.0 | testkit-core | `StringUtils`, `ReflectionUtils` utilities |
| `okhttp` | 4.12.0 | testkit-api, testkit-security | HTTP client |
| `json-path` | 2.9.0 | testkit-api | JSONPath evaluation |
| `javafaker` | 1.0.2 | testkit-seed | Realistic fake data generation |
| `snakeyaml` | 2.2 | testkit-seed | YAML fixture loading |
| `HikariCP` | 5.1.0 | testkit-seed, testkit-db | JDBC connection pooling |
| `wiremock` | 3.5.4 | testkit-mock | Embedded HTTP stub server |
| `HdrHistogram` | 2.2.2 | testkit-load | Latency percentile tracking |
| `kafka-clients` | 3.7.0 | testkit-queue | Kafka producer/consumer |
| `amqp-client` | 5.21.0 | testkit-queue | RabbitMQ producer/consumer |
| `assertj-core` | 3.25.3 | test scope | Fluent assertions in tests |
| `spring-boot-autoconfigure` | 3.2.5 | testkit-spring | `@AutoConfiguration`, `@ConfigurationProperties` |

Build requirements: **Java 21** (preview features via `--enable-preview` in compiler and surefire args).

---

## 6. Extension Points

TestKit exposes three SPI interfaces for extensibility:

### 6.1 Custom Steps (`TestKitStep`)

```java
public interface TestKitStep {
    String name();
    StepResult execute(TestKitContext ctx);
}
```

Implement this to add any custom step to the pipeline:

```java
TestKitDsl.test("Payment Flow")
    .step(new MyCustomCleanupStep())
    .api(a -> a.POST("/api/payments")...)
    .run();
```

### 6.2 Custom Reporters (`TestKitReporter`)

```java
public interface TestKitReporter {
    default void onSuiteStart(String suiteName) {}
    default void onStepStart(String stepName) {}
    default void onStepEnd(StepResult result) {}
    default void onSuiteEnd(TestKitResult result) {}
}
```

Example — publishing results to Slack:

```java
.reporter(new SlackReporter(webhookUrl))
```

### 6.3 Custom Security Probes (`SecurityProbe`)

```java
public interface SecurityProbe {
    String name();
    List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig);
}
```

Register via:

```java
.security(s -> s.probe("/api/payments", new MyCustomHeaderInjectionProbe()))
```

---

## 7. Full API Reference

### Entry Points

```java
// Standalone
TestKitDsl.test(String suiteName) → TestKitDsl

// Spring Boot
@Autowired TestKitFactory factory;
factory.test(String suiteName)   → TestKit
```

### Configuration

```java
.config(c -> c
    .baseUrl("http://localhost:8080")
    .timeout(Duration.ofSeconds(15))
    .failFast(true)
    .verbose(false))
.reporter(new JsonReporter(Path.of("target/testkit-report.json")))
```

### Seed

```java
.seed(s -> s
    .dataSource(ds)
    .table("users").fromFactory(new UserFactory(), 5)
    .table("products").fromFixture("fixtures/products.json")
    .table("tags").fromRows(List.of(Map.of("name","java")))
    .truncate("audit_log")
    .sql("UPDATE settings SET val=? WHERE key=?", "dark", "theme"))
```

### Mock

```java
.mock(m -> m
    .port(9090)                              // or omit for random port
    .contextKey("mockBaseUrl")               // stores http://localhost:9090 in ctx
    .stub(s -> s
        .GET("/payments/health")
        .willReturn(200, """{"status":"UP"}"""))
    .stub(s -> s
        .POST("/payments/charge")
        .withBody("\"amount\"")
        .delay(100)
        .willReturn(201, """{"chargeId":"c1"}""")))
```

### API

```java
.api(a -> a
    .POST("/api/orders")
    .header("X-Tenant", "acme")
    .bearerToken("my-jwt")
    .json("""{"productId":"p1","qty":2}""")
    .expect(201)
    .assertJsonPath("$.status", "PENDING")
    .assertJsonPathPresent("$.id")
    .assertHeader("Location", "/api/orders/1")
    .extract("orderId", "$.id")
    .extract("status",  "$.status"))

.api("Fetch order", a -> a
    .GET("/api/orders/{id}")
    .pathParam("id", ctx -> ctx.get("orderId"))   // dynamic from context
    .expect(200))
```

### Database Assertions

```java
.db(d -> d
    .dataSource(ds)
    .query("SELECT * FROM orders WHERE id = ?", ctx -> ctx.get("orderId"))
        .assertRowCount(1)
        .assertColumn("status", "PENDING")
        .assertColumnNotNull("created_at")
        .end()
    .assertExists("users", "email = ?", "test@example.com")
    .assertCount("orders", 1)
    .extract("rowId", "SELECT id FROM orders ORDER BY created_at DESC LIMIT 1"))
```

### Queue

```java
// Kafka
.queue(q -> q
    .kafka("localhost:9092")
    .produce("order-commands", """{"action":"CREATE","qty":1}""")
    .assertMessageOnTopic("order-events", "\"status\":\"CREATED\"", Duration.ofSeconds(5))
    .consumeToContext("lastEvent", "order-events", Duration.ofSeconds(3)))

// RabbitMQ
.queue(q -> q
    .rabbit("localhost")
    .purgeQueue("email-inbox")
    .publishToQueue("email-inbox", """{"to":"user@example.com"}""")
    .assertMessageOnQueue("email-inbox", "user@example.com", Duration.ofSeconds(3)))
```

### Security

```java
.security(s -> s
    .baseUrl("http://localhost:8080")
    .sqlInjection("/api/search")
    .xss("/api/feedback")
    .authBypass("/api/admin/users")
    .rateLimit("/api/auth/login")
    .sensitiveDataExposure("/api/users/me")
    .failOnSeverity(SecurityFinding.Severity.HIGH))

// Full scan shorthand
.security(s -> s.fullScan("/api/orders").failOnAnyFinding())

// Custom probe
.security(s -> s.probe("/api/payments", new CsrfProbe()))
```

### Contract

```java
// Consumer side — verify and save
.contract(c -> c
    .consumer("order-service").provider("inventory-service")
    .describe("Get product stock")
    .request(r -> r.GET("/api/products/{id}").pathParam("id","123"))
    .expectStatus(200)
    .expectBodyField("$.stock")
    .expectBodyField("$.sku", "WIDGET-1")
    .saveContractTo(Path.of("contracts/order-inventory.json")))

// Provider side — verify saved contract
.contract(c -> c
    .verifyContract(Path.of("contracts/order-inventory.json"))
    .request(r -> r.GET("/api/products/123")))
```

### Load

```java
.load(l -> l
    .scenario("Checkout under load")
    .POST("/api/checkout")
    .json("""{"cartId":"cart-1"}""")
    .expect(200)
    .virtualUsers(100)
    .rampUp(Duration.ofSeconds(10))
    .duration(60)                       // seconds shorthand
    .rps(500)
    .p50Under(100)
    .p99Under(500)
    .maxUnder(2000)
    .errorRateUnder(0.5))

// Custom scenario
.load(l -> l
    .scenario(new MultiStepCheckoutScenario())
    .virtualUsers(50)
    .duration(30)
    .p99Under(800))
```

### Execution

```java
// Returns result without throwing
TestKitResult result = .run();
result.passed()       // boolean
result.passCount()    // long
result.failCount()    // long
result.steps()        // List<StepResult>

// Throws TestKitException if any step failed
.runAndAssert()
```

---

*Document generated from TestKit source at version 0.1.0-SNAPSHOT.*
