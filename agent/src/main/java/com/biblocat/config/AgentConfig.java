package com.biblocat.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public record AgentConfig(
        Path rootDir,
        int scanPeriodSeconds,
        int scanMaxDepth,
        int pollIntervalSeconds,
        int hashTimeoutSeconds,
        int hashMaxFileSizeMb,
        int hashMaxRetries,
        int batchSize,
        int retryMaxAttempts,
        int retryBackoffSeconds,
        int shutdownGracePeriodSeconds,
        String apiBaseUrl
) {

    private static final String DEFAULT_CONFIG = "agent.properties";
    private static final String ENV_CONFIG_OVERRIDE = "BIBLOCAT_AGENT_CONFIG";

    public static AgentConfig load() {
        var props = loadProperties();
        var rootDir = resolveRootDir(props.getProperty("biblocat.agent.scan.root-dir"));
        return new AgentConfig(
                rootDir,
                getInt(props, "biblocat.agent.scan.period-seconds", 300),
                getInt(props, "biblocat.agent.scan.max-depth", 10),
                getInt(props, "biblocat.agent.poll.interval-seconds", 30),
                getInt(props, "biblocat.agent.hash.timeout-seconds", 30),
                getInt(props, "biblocat.agent.hash.max-file-size-mb", 500),
                getInt(props, "biblocat.agent.hash.max-retries", 3),
                getInt(props, "biblocat.agent.batch.size", 50),
                getInt(props, "biblocat.agent.retry.max-attempts", 3),
                getInt(props, "biblocat.agent.retry.backoff-seconds", 2),
                getInt(props, "biblocat.agent.shutdown.grace-period-seconds", 5),
                getString(props, "biblocat.agent.api.base-url", "http://localhost:8080")
        );
    }

    private static Properties loadProperties() {
        var props = new Properties();

        try (var in = openDefaultConfig()) {
            props.load(in);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load default config from classpath", e);
        }

        var overridePath = System.getenv(ENV_CONFIG_OVERRIDE);
        if (overridePath != null && !overridePath.isBlank()) {
            var overrideFile = Paths.get(overridePath);
            if (Files.exists(overrideFile)) {
                try (var in = Files.newInputStream(overrideFile)) {
                    props.load(in);
                } catch (IOException e) {
                    throw new ConfigurationException("Failed to load override config: " + overridePath, e);
                }
            }
        }

        return props;
    }

    private static InputStream openDefaultConfig() {
        var in = AgentConfig.class.getClassLoader().getResourceAsStream(DEFAULT_CONFIG);
        if (in == null) {
            throw new ConfigurationException("Default config '" + DEFAULT_CONFIG + "' not found on classpath");
        }
        return in;
    }

    private static Path resolveRootDir(String rootDir) {
        if (rootDir == null || rootDir.isBlank()) {
            throw new ConfigurationException("biblocat.agent.scan.root-dir is required but not configured");
        }
        var path = Paths.get(rootDir);
        if (!Files.exists(path)) {
            throw new ConfigurationException("Root directory does not exist: " + rootDir);
        }
        if (!Files.isDirectory(path)) {
            throw new ConfigurationException("Root path is not a directory: " + rootDir);
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new ConfigurationException("Failed to resolve root directory real path: " + rootDir, e);
        }
    }

    private static String getString(Properties props, String key, String defaultValue) {
        var value = props.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value.strip();
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        var value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid integer value for '" + key + "': " + value);
        }
    }
}
