# domain-util

Command → Event pipeline for Spring Boot. Add the dependency, provide an audit entity + repository, then invoke commands from a controller.

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.2.7</version>
</dependency>
```

Java 17+, Spring Boot 3.x / 4.x.

## 1. Audit entity

Implement `AuditEvent` with a no-arg constructor. `@Table` decides where rows are stored.

```java
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "event", schema = "blog_system")
public class BlogcommentEvent implements AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

## 2. Audit repository

```java
public interface BlogcommentEventRepository extends AuditEventRepository<BlogcommentEvent> {
}
```

With exactly one `AuditEventRepository` bean, the library creates `CommandInvoker` for you. Inject that — do not subclass the auditor, invoker, or event logger.

## 3. Customize audit writes (`AuditConfiguration`)

The library creates one empty `AuditConfiguration` bean. Override it with a `@Bean` or a `@Configuration` subclass. Auto-config skips its default when yours is present (`@ConditionalOnMissingBean`).

That one class is the whole consumer API: command-row handlers, domain-event-row handlers, and a callback that sees **every** audit row for the request after the command invoker has finished (including `failure_reason` on a failed chain).

```java
import com.machingclee.domain.util.common.audit.AfterTransactionRecord;
import com.machingclee.domain.util.common.audit.AuditConfiguration;
import com.machingclee.domain.util.common.audit.AuditTx;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomAuditConfiguration extends AuditConfiguration {

    public CustomAuditConfiguration() {
        // --- per command-audit row (written before the business TX) ---
        addPreCommandAuditHandler(record -> {
            // JOIN: mutate the row atomically with save
            record.getAuditEvent().setPayload(/* redacted */);
        }, AuditTx.JOIN);
        addPostCommandAuditHandler(record -> {
            // REQUIRES_NEW: extra sink; throw does not undo the row
        }, AuditTx.REQUIRES_NEW);
        addPostCommandAuditHandler(record -> {
            // JOIN: still in persist TX — throw rolls back the command audit row
        }, AuditTx.JOIN);

        // --- per domain-event audit row ---
        addPreEventAuditHandler(record -> {
            Object domainEvent = record.getDomainEvent();
        }, AuditTx.JOIN);
        addPostEventAuditHandler(record -> { /* metrics / extra sink */ }, AuditTx.REQUIRES_NEW);

        // --- once per top-level invoke, after TX + failure stamps ---
        addAfterTransactionHandler(this::onRequestFinished);
    }

    private void onRequestFinished(AfterTransactionRecord record) {
        String requestId = record.getRequestId();
        boolean committed = record.isCommitted();
        // findAllByRequestId: command rows + domain-event rows
        for (AuditEvent row : record.getEvents()) {
            Boolean success = row.getSuccess();
            String type = row.getEventType();
            String failure = row.getFailureReason(); // set on a failed chain
        }
    }
}
```

Handlers wrap `eventRepository.save`. They are **not** `CommandHandler`. They receive a record envelope:

| Record | When | Fields |
| --- | --- | --- |
| `CommandAuditRecord` | Each command audit insert | `getCommand()`, `getRequestId()`, `getAuditEvent()` |
| `EventAuditRecord` | Each domain-event audit insert | `getDomainEvent()`, `getRequestId()`, `getAuditEvent()`, `getWrapper()` |
| `AfterTransactionRecord` | Once, after the top-level invoke | `getRequestId()`, `isCommitted()`, `getEvents()` |

`getAuditEvent()` is the row about to be saved. Mutate that instance; do not replace it.

### Row pipeline (pre / post)

Same thread, **not** a new thread:

```
REQUIRES_NEW pres
  → persist TX (JOIN pres → original/override save → JOIN posts)
  → REQUIRES_NEW posts
```

Every `addPre*` / `addPost*` call must pass `AuditTx`. There is no default.

| `AuditTx` | Meaning |
| --- | --- |
| `JOIN` | Same TX as `eventRepository.save`. A throw rolls back that audit row. |
| `REQUIRES_NEW` | Own TX. A throw is logged and swallowed. |

Audit failures never abort `CommandHandler`. A `JOIN` throw only rolls back the audit insert; invoke continues.

### After-transaction snapshot (`addAfterTransactionHandler`)

This is **not** Spring `TransactionSynchronization.afterCompletion`. That callback would run *before* the invoker stamps `failure_reason`.

The command invoker runs this handler once per **top-level** `invoke`, in `finally`, **after**:

1. the command transaction commits or rolls back
2. POST_COMMIT domain-event rows exist (success path)
3. `logFailure` on the command row and `markEventsFailed` on the domain-event rows (failure path)

So `record.getEvents()` is `findAllByRequestId` for that request: the command row, nested commands that reused the same request id, immediate events, and (on commit) POST_COMMIT events. On a failed chain those rows already have `success=false` and `failure_reason`.

Nested `invoker.invoke` (policies) does **not** fire the handler again — the top-level invoke owns the snapshot.

A throw from the handler is logged and swallowed; it does not change the command result.

### Replace save (override)

`overrideCommandAuditHandler` / `overrideEventAuditHandler` **replace** the repository save. Call `getOriginalCommandAuditHandler()` / `getOriginalEventAuditHandler()` to still persist. Skip that call to skip DB.

```java
@Configuration
public class CustomAuditConfiguration extends AuditConfiguration {

    public CustomAuditConfiguration() {
        overrideCommandAuditHandler(record -> {
            getOriginalCommandAuditHandler().handle(record); // identity = default save
        });
        overrideEventAuditHandler(record -> {
            // skip repository.save — original is not invoked
        });
    }
}
```

Those original handlers are only `eventRepository.save(record.getAuditEvent())`. They do not re-run pre/post.

## 4. Use it in a controller

```java
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommandInvoker commandInvoker;

    @PostMapping("/comments")
    public void create(@RequestBody CreateCommentRequest request) throws Exception {
        commandInvoker.invoke(new CreateCommentCommand(request.body()));
    }
}
```

```java
public record CreateCommentCommand(String body) implements Command<Void> {}

@Component
public class CreateCommentHandler implements CommandHandler<CreateCommentCommand, Void> {
    @Override
    public Void handle(EventQueue eventQueue, CreateCommentCommand command) {
        // persist, then eventQueue.add(...) if you raise domain events
        return null;
    }
}
```

Queries work the same way: simply inject `QueryHandler` which is already created by the library.

## 5. `application.yml` (optional — docs roles only)

This block is **optional**. Omit it entirely unless we want `/docs` to show real controller roles instead of the `@Actor` labels on Command / Query types.

The diagram uses `@Actor("Admin")` by default. If a command or query is invoked from an HTTP endpoint, and that endpoint carries a role-list annotation (for example `@RequiresRole(role = {ADMIN})`), we can point the scanner at that annotation. Non-empty scanned roles then **replace** `@Actor` on that node. Empty / missing roles leave `@Actor` in place.

Startup, `CommandInvoker`, queries, audit rows, and `/docs` itself all work without this YAML. Missing properties are the same as:

```yaml
domain-util:
  docs:
    auth-annotation: ""          # role scanning off
    auth-roles-attribute: role   # unused until scanning is on
```

Only add the block when we actually want that replacement:

```yaml
domain-util:
  docs:
    auth-annotation: com.example.security.RequiresRole
    auth-roles-attribute: role
```

`com.example.security.RequiresRole` is a placeholder. Use the real annotation in the consumer app. A marker annotation with no role list (for example `@RequireGoogleAuth`) is not useful here.

| Property | Meaning |
|---|---|
| `domain-util.docs.auth-annotation` | Optional. Fully-qualified controller annotation that lists authorized roles. Omit the property, or set `""`, to skip role scanning and keep `@Actor` on the diagram. |
| `domain-util.docs.auth-roles-attribute` | Attribute on that annotation that holds the roles (`Enum[]`, `String[]`, a single `Enum`, or a single `String`). Defaults to `role`. Unused when scanning is off. |

Apache License 2.0 — see [LICENSE](LICENSE).
