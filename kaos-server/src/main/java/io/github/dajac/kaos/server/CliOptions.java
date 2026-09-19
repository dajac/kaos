package io.github.dajac.kaos.server;

import java.util.Arrays;

/**
 * The command line options of the server.
 *
 * @param bootstrapServer the Kafka bootstrap servers the simulator connects to
 * @param port the HTTP port; 0 picks a free port
 */
record CliOptions(String bootstrapServer, int port) {

    static final String DEFAULT_BOOTSTRAP_SERVER = "localhost:9092";
    static final int DEFAULT_PORT = 8081;

    static final String USAGE = """
        Usage: ./run.sh [options]
               java -jar kaos-server-<version>.jar [options]

          --bootstrap-server <host:port>  Kafka bootstrap servers (default: %s)
          --port <port>                   HTTP port (default: %d)
          --help                          Print this help and exit
        """.formatted(DEFAULT_BOOTSTRAP_SERVER, DEFAULT_PORT);

    /**
     * Returns whether the command line asks for the usage text.
     */
    static boolean isHelp(String[] args) {
        return Arrays.asList(args).contains("--help");
    }

    /**
     * Parses the command line.
     *
     * @throws IllegalArgumentException with a user-facing message if the arguments are invalid
     */
    static CliOptions parse(String[] args) {
        String bootstrapServer = DEFAULT_BOOTSTRAP_SERVER;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--bootstrap-server" -> bootstrapServer = value(args, ++i, option);
                case "--port" -> port = parsePort(value(args, ++i, option));
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }

        if (bootstrapServer.isBlank()) {
            throw new IllegalArgumentException("Empty value for option: --bootstrap-server");
        }
        return new CliOptions(bootstrapServer, port);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for option: " + option);
        }
        return args[index];
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + value);
        }
    }
}
