package io.github.dajac.kaos.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * The version of the running build. Maven writes it into {@code version.properties} when it filters
 * the resources. picocli instantiates this class to print {@code --version}.
 */
final class Version implements IVersionProvider {

    private static final String RESOURCE = "version.properties";

    Version() {
    }

    @Override
    public String[] getVersion() {
        return new String[] {"Kaos " + read()};
    }

    /**
     * Reads the build version from the classpath.
     *
     * @throws IllegalStateException if the resource is missing or has no {@code version} entry
     */
    static String read() {
        Properties properties = new Properties();
        try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("No version entry in " + RESOURCE);
        }
        return version;
    }
}
