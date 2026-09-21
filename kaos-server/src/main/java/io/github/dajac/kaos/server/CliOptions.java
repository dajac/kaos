package io.github.dajac.kaos.server;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

/**
 * The command line options of the server. picocli fills the fields when it parses the command
 * line; {@link Main} mixes them into its command.
 */
final class CliOptions {

    static final String DEFAULT_BOOTSTRAP_SERVER = "localhost:9092";
    static final int DEFAULT_PORT = 8081;

    @Option(
        names = "--bootstrap-server",
        paramLabel = "<host:port>",
        defaultValue = DEFAULT_BOOTSTRAP_SERVER,
        converter = BootstrapServerConverter.class,
        description = "Kafka bootstrap servers (default: ${DEFAULT-VALUE})"
    )
    private String bootstrapServer = DEFAULT_BOOTSTRAP_SERVER;

    @Option(
        names = "--port",
        paramLabel = "<port>",
        defaultValue = "" + DEFAULT_PORT,
        converter = PortConverter.class,
        description = "HTTP port; 0 picks a free port (default: ${DEFAULT-VALUE})"
    )
    private int port = DEFAULT_PORT;

    /**
     * Returns the Kafka bootstrap servers the simulator connects to.
     */
    String bootstrapServer() {
        return bootstrapServer;
    }

    /**
     * Returns the HTTP port; 0 picks a free port.
     */
    int port() {
        return port;
    }

    /**
     * Rejects a blank bootstrap server list.
     */
    static final class BootstrapServerConverter implements ITypeConverter<String> {
        @Override
        public String convert(String value) {
            if (value.isBlank()) {
                throw new TypeConversionException("the value must not be blank");
            }
            return value;
        }
    }

    /**
     * Accepts the ports 0 to 65535.
     */
    static final class PortConverter implements ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) {
            int port;
            try {
                port = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new TypeConversionException("'" + value + "' is not a number");
            }
            if (port < 0 || port > 65535) {
                throw new TypeConversionException("port " + value + " is out of range (0-65535)");
            }
            return port;
        }
    }
}
