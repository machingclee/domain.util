# domain-util

Command → Event pipeline for Spring Boot. Add the dependency, provide an audit entity + repository, then invoke commands from a controller.

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.2.5</version>
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

## 3. Use it in a controller

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

## 4. `application.yml` (optional — docs roles only)

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
