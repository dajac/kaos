package io.github.dajac.kaos.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command line entry point. It parses the options and starts the {@link Server}.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(CliOptions.USAGE);
            System.exit(2);
            return;
        }

        Server server = new Server();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "kaos-shutdown"));
        server.start(options.port());

        LOG.info("Kaos {} listening on http://localhost:{} (bootstrap: {})",
            Version.read(), server.port(), options.bootstrapServer());
    }
}
