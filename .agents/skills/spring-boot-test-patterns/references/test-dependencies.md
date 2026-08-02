# Test Dependencies Setup

## Maven Dependencies

### Basic Testing Setup

```xml

<dependencies>
    <!-- Spring Boot Test Starter (includes JUnit 5, Mockito, AssertJ) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Spring Boot Testcontainers integration (@ServiceConnection) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Testcontainers JUnit 5 integration -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- PostgreSQL Testcontainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-postgresql</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- MySQL Testcontainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-mysql</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- Additional Dependencies -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

### Gradle Dependencies

```kotlin
dependencies {
    // Spring Boot Test Starter
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Spring Boot Testcontainers integration (@ServiceConnection)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")

    // Additional Dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

## Version Selection

- **Spring Boot 4.x**: Testcontainers 2.x, version managed by the Spring Boot BOM (property
  `${testcontainers.version}`). ArtifactIds use the `testcontainers-` prefix (`testcontainers-junit-jupiter`,
  `testcontainers-postgresql`, ...) — the classic 1.x names (`junit-jupiter`, `postgresql`) do not exist in 2.x.
- **Spring Boot 3.x**: Testcontainers 1.19.x-1.21.x (classic artifactIds)
- **Spring Boot 2.x**: Testcontainers 1.17.x
- Always check [Testcontainers Documentation](https://testcontainers.org/) for latest versions

## Optional Testing Dependencies

### H2 In-Memory Database

```xml

<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

### WireMock for HTTP Mocking

```xml

<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.5.2</version>
    <scope>test</scope>
</dependency>
```

### Awaitility for Async Testing

```xml

<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.0</version>
    <scope>test</scope>
</dependency>
```
