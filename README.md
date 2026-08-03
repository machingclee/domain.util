# domain-util

Spring Boot library for a **Command → Event → Policy** (CQRS-style) pipeline.

Maven coordinates:

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.1.4-SNAPSHOT</version>
</dependency>
```

Java package root: `com.machingclee.domain.util`

## Features

- Command / query invokers with handler discovery
- Domain event dispatch (Spring `ApplicationEventPublisher`)
- Optional audit event logging hooks
- Built-in command-flow docs + visualization under `/docs` (when a command invoker bean is present)

## Requirements

- Java 17+
- Spring Boot 3.x / 4.x (most Spring deps are `optional` — bring what your app uses)

## Design note: no multi-schema routing

This library is **single-pipeline** by default:

- One `CommandInvoker` registers **all** `CommandHandler` beans in the application context
- One `DomainEventLogger` persists **all** domain events via its injected repository
- **Where rows are stored** is decided only by your JPA setup:
  - entity `@Table(name = "...", schema = "...")` (optional Postgres schema)
  - which `AuditEventRepository` / datasource you inject

There is **no** `@TargetSchema` / `SchemaIdentifier` API. Prefer separate applications or separate persistence units if you need multiple event stores.

## Quick consumer setup

1. Add the dependency above (local install or GitHub Packages once published).

2. **Create these Spring beans** (names below use a `SomeDomain` placeholder — rename for your domain):

   | Class | Extends / implements | Role |
   |-------|----------------------|------|
   | `SomeDomainEvent` | `AuditEvent` | JPA entity for command/event audit rows (`@Table` decides storage) |
   | `SomeDomainEventRepository` | `AuditEventRepository<SomeDomainEvent>` | Persistence for audit rows |
   | `SomeDomainCommandAuditor` | `CustomCommandAuditor<SomeDomainEvent>` | Writes command audit rows |
   | `SomeDomainCommandInvoker` | `AbstractCommandInvoker<SomeDomainEvent>` | Dispatches all commands |
   | `SomeDomainDomainEventLogger` | `DomainEventLogger` | Persists all domain events |

   ```java
   // SomeDomainCommandAuditor.java
   @Component
   public class SomeDomainCommandAuditor extends CustomCommandAuditor<SomeDomainEvent> {
       public SomeDomainCommandAuditor(SomeDomainEventRepository eventRepository) {
           super(eventRepository, SomeDomainEvent::new);
       }
   }
   ```

   ```java
   // SomeDomainCommandInvoker.java
   @Component
   public class SomeDomainCommandInvoker extends AbstractCommandInvoker<SomeDomainEvent> {
       public SomeDomainCommandInvoker(
               ApplicationContext context,
               DomainEventDispatcher domainEventDispatcher,
               PlatformTransactionManager transactionManager,
               SomeDomainCommandAuditor auditor,
               SomeDomainEventRepository eventRepository
       ) {
           super(
                   context,
                   domainEventDispatcher,
                   transactionManager,
                   auditor,
                   eventRepository
           );
       }
   }
   ```

   ```java
   // SomeDomainDomainEventLogger.java
   @Component
   public class SomeDomainDomainEventLogger extends DomainEventLogger {
       public SomeDomainDomainEventLogger(
               SomeDomainEventRepository eventRepository,
               ApplicationEventPublisher publisher
       ) {
           super(eventRepository, SomeDomainEvent::new, publisher);
       }
   }
   ```

   Prefer **one** invoker and **one** event logger per application. Multiple loggers would each receive every event and may double-write.

3. Implement handlers (no schema annotation required):

   ```java
   @Component
   public class CreateSomethingHandler implements CommandHandler<CreateSomethingCommand, Void> {
       // ...
   }
   ```

## Event-storming frontend

The React/Vite UI that powers the bundled command visualization lives in
`event-storming-frontend/`. Built assets are shipped inside the JAR under
`src/main/resources/META-INF/resources/command-visualization/`.

```bash
cd event-storming-frontend
npm install
npm run build
# then copy dist/ into src/main/resources/META-INF/resources/command-visualization/
```

## Build locally

```bash
mvn clean install -DskipTests
```


## Publish to GitHub Packages

`mvn deploy` uploads artifacts to whatever is configured under
`<distributionManagement>` in `pom.xml` (here: GitHub Packages for
`machingclee/domain.util`). It does **not** push git commits; that is still
`git push`.

Prefer CI so no PAT is stored on a laptop:

1. Push this repo to GitHub (already: `machingclee/domain.util`).
2. On a release tag, or via **Actions → Publish to GitHub Packages → Run workflow**:

```bash
git tag v0.1.0-SNAPSHOT
git push origin v0.1.0-SNAPSHOT
# or open the workflow_dispatch UI
```

The workflow `.github/workflows/publish.yml` runs `mvn clean deploy` with
`GITHUB_TOKEN` (`packages: write`). Local `~/.m2/settings.xml` is optional.

Consumers still need a repository block pointing at
`https://maven.pkg.github.com/machingclee/domain.util` and a token with
`read:packages` to download (GitHub usually requires auth even for public packages).

## Origin

Extracted and rebranded from an internal `domain.util` module used in multi-module Spring Boot services. This repository is the standalone, personal open-source form under `com.machingclee`. Multi-schema routing from that origin was removed in favor of single-pipeline + `@Table`-based persistence.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
