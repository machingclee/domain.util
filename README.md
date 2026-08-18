# domain-util

Spring Boot library for a **Command → Event → Policy** (CQRS-style) pipeline.

Maven coordinates:

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.1.16</version>
</dependency>
```

Java package root: `com.machingclee.domain.util`

## Features

- Command / query invokers with handler discovery
- Domain event dispatch (Spring `ApplicationEventPublisher`)
- Audit event logging (auto-wired once you provide an `AuditEvent` entity + `AuditEventRepository`)
- Built-in command-flow docs + visualization under `/docs` (when a command invoker bean is present)

## Requirements

- Java 17+
- Spring Boot 3.x / 4.x (most Spring deps are `optional` — bring what your app uses)


## Quick consumer setup

1. Add the dependency above (Maven Central once published, no extra `<repository>` needed).

2. **Provide an audit entity + repository.** Auto-config then waits for **exactly one**
   `AuditEventRepository` bean and creates `CommandAuditor`, `CommandInvoker`, and
   `DomainEventLogger`. There is no separate “wait for entity bean” step — JPA entities
   are not Spring beans. Spring Data only registers the repository after the matching
   `@Entity` exists and is in the persistence unit, and the generic
   `AuditEventRepository<BlogcommentEvent>` already requires that type at compile time.

   | You write | Role |
   |-----------|------|
   | `BlogcommentEvent` implements `AuditEvent` | JPA entity for command/event audit rows (`@Table` decides storage). Needs a no-arg constructor (Lombok `@NoArgsConstructor` is fine; protected is OK). |
   | `BlogcommentEventRepository extends AuditEventRepository<BlogcommentEvent>` | Persistence for audit rows. Extra query methods are yours. |

   Inject `CommandInvoker` (same idea as `QueryInvoker`). Do **not** declare your own
   auditor / invoker / logger unless you need to override them (`@ConditionalOnMissingBean`)
   or you have **more than one** `AuditEventRepository` (multi-PU). In that case auto-config
   stays out of the way and you wire the three beans yourself.

   Prefer **one** invoker and **one** event logger per application. Multiple loggers would
   each receive every event and may double-write.

   Controllers / policies inject the port:

   ```java
   private final CommandInvoker commandInvoker;
   ```

### Entity

```java
package com.machingclee.blogcomment.common.jpa.entity;

import com.machingclee.domain.util.annotation.BoundedContext;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit trail for commands and domain events.
 * Physical storage: the "event" table in the "blog_system" Postgres schema
 * (owned by the Prisma migrations in db/prisma — see db/prisma/schema.prisma).
 */
@BoundedContext("Blog Comments")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "event", schema = "blog_system")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BlogcommentEvent implements AuditEvent {

    @Setter(AccessLevel.NONE)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "created_at")
    private Double createdAt;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "event_order")
    private Integer eventOrder;

    @Column(name = "request_user_email")
    private String requestUserEmail;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason = "";
}
```

### Repository

```java
package com.machingclee.blogcomment.common.jpa.repository;

import com.machingclee.blogcomment.common.jpa.entity.BlogcommentEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BlogcommentEventRepository extends AuditEventRepository<BlogcommentEvent> {

    List<BlogcommentEvent> findAllByRequestIdAndEventType(String requestId, String eventType);

    @Query("""
                select e from BlogcommentEvent e
                where (:requestId IS NULL OR e.requestId = :requestId)
                  and (:success IS NULL OR e.success = :success)
                order by e.createdAt desc, e.eventOrder desc
            """)
    Page<BlogcommentEvent> findByPageAndLimit(
            @Param("requestId") String requestId,
            @Param("success") Boolean success,
            Pageable pageable);
}
```

### Optional override (only if auto-config is not enough)

Skip this when you have a single audit store. Declare these only to customize
behavior or to wire **multiple** `AuditEventRepository` beans:

```java
package com.machingclee.blogcomment.common.domainutils.infra;

import com.machingclee.domain.util.common.command.CustomCommandAuditor;
import com.machingclee.blogcomment.common.jpa.entity.BlogcommentEvent;
import com.machingclee.blogcomment.common.jpa.repository.BlogcommentEventRepository;
import org.springframework.stereotype.Component;

/**
 * Persists one BlogcommentEvent row per command (and related audit helpers)
 * in REQUIRES_NEW transactions so the trail can survive outer rollbacks.
 */
@Component
public class CommandAuditor extends CustomCommandAuditor<BlogcommentEvent> {
    public CommandAuditor(BlogcommentEventRepository eventRepository) {
        super(eventRepository, BlogcommentEvent::new);
    }
}
```

```java
package com.machingclee.blogcomment.common.domainutils.infra;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;
import com.machingclee.blogcomment.common.jpa.entity.BlogcommentEvent;
import com.machingclee.blogcomment.common.jpa.repository.BlogcommentEventRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Entry point for executing Commands.
 * Orchestration (tx, audit, event dispatch) lives in {@link AbstractCommandInvoker}.
 * This subclass only injects auditor + event repository + transaction manager.
 * Physical storage is controlled by {@link BlogcommentEvent}'s {@code @Table}.
 * <p>
 * Registers all CommandHandler beans in the application context (single pipeline).
 */
@Component
public class CommandInvoker extends AbstractCommandInvoker<BlogcommentEvent> {

    public CommandInvoker(
            ApplicationContext context,
            DomainEventDispatcher domainEventDispatcher,
            PlatformTransactionManager transactionManager,
            CommandAuditor auditor,
            BlogcommentEventRepository eventRepository
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
package com.machingclee.blogcomment.common.domainutils.infra;

import com.machingclee.domain.util.common.event.DomainEventLogger;
import com.machingclee.blogcomment.common.jpa.entity.BlogcommentEvent;
import com.machingclee.blogcomment.common.jpa.repository.BlogcommentEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Persists domain events raised during command handling.
 * Prefer a single DomainEventLogger bean per application to avoid double-writes.
 */
@Component
public class BlogcommentDomainEventLogger extends DomainEventLogger {
    public BlogcommentDomainEventLogger(
            BlogcommentEventRepository eventRepository,
            ApplicationEventPublisher publisher
    ) {
        super(eventRepository, BlogcommentEvent::new, publisher);
    }
}
```

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

### `application.yml`

For `/docs` to show which roles can invoke each command/query, point the
scanner at your app's controller auth annotation in `application.yml`:

```yaml
domain-util:
  docs:
    auth-annotation: com.example.security.RequiresRole
    auth-roles-attribute: role
```

- `auth-annotation` — fully-qualified name of the annotation on controller
  methods that declares authorized roles. Required; use `""` to disable role
  scanning.
- `auth-roles-attribute` — attribute on that annotation that holds the role
  list (`Enum[]`, `String[]`, a single `Enum`, or a single `String`). Defaults
  to `role`.

## Build locally

```bash
mvn clean install -DskipTests
```


## Publish to Maven Central

Releases go to the [Sonatype Central Publisher Portal](https://central.sonatype.com)
(namespace `com.machingclee`). Consumers only need the dependency coordinates above.

### One-time setup

1. Verify namespace `com.machingclee` in the Portal (DNS TXT on `machingclee.com`).
2. Generate a Portal **user token**.
3. Create a GPG key, upload the public key to a keyserver, and confirm identity.
4. Put the token in `~/.m2/settings.xml`:

```xml
<server>
  <id>central</id>
  <username><!-- portal token username --></username>
  <password><!-- portal token password --></password>
</server>
```

### Local deploy

```bash
export GPG_TTY=$(tty)
# First time: autoPublish stays false (pom default) — review then Publish in the Portal.
mvn -B clean deploy -DskipTests

# Later releases can auto-publish:
# mvn -B clean deploy -DskipTests -Dcentral.autoPublish=true
```

`mvn deploy` does **not** push git; use `git tag` / `git push` separately.

### CI

`.github/workflows/publish.yml` runs on tags `v*` or **workflow_dispatch**.
Configure repository secrets: `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`,
`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

## Origin

Extracted and rebranded from an internal `domain.util` module used in multi-module Spring Boot services. This repository is the standalone, personal open-source form under `com.machingclee`. Multi-schema routing from that origin was removed in favor of single-pipeline + `@Table`-based persistence.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
