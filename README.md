# domain-util

Spring Boot library for a **Command → Event → Policy** (CQRS-style) pipeline.

Maven coordinates:

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Java package root: `com.machingclee.domain.util`

## Features

- Command / query invokers with handler discovery
- Domain event dispatch (Spring `ApplicationEventPublisher`)
- Schema-targeted handlers via `@TargetSchema` + `SchemaIdentifier`
- Compile-time enforcement that concrete `CommandHandler`s declare `@TargetSchema`
- Optional audit event logging hooks
- Built-in command-flow docs + visualization under `/docs` (when a command invoker bean is present)

## Requirements

- Java 17+
- Spring Boot 3.x / 4.x (most Spring deps are `optional` — bring what your app uses)

## Quick consumer setup

1. Add the dependency above (local install or GitHub Packages once published).
2. Define your schema:

```java
public enum SalesSchema implements SchemaIdentifier {
    SALES;

    @Override
    public String schemaName() {
        return name().toLowerCase();
    }
}
```

3. Annotate handlers:

```java
@TargetSchema(SalesSchema.class)
@Component
public class CreateOrderHandler implements CommandHandler<CreateOrderCommand, Void> {
    // ...
}
```

4. If you already list Lombok / MapStruct under `maven-compiler-plugin`  
   `<annotationProcessorPaths>`, also add this library there so the  
   `@TargetSchema` processor is still applied:

```xml
<path>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</path>
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

## Origin

Extracted and rebranded from an internal `domain.util` module used in multi-module Spring Boot services. This repository is the standalone, personal open-source form under `com.machingclee`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
