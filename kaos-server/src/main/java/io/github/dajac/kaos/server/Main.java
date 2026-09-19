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
        if (CliOptions.isHelp(args)) {
            System.out.print(CliOptions.USAGE);
            return;
        }

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
        try {
            server.start(options.port());
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "kaos-shutdown"));

        LOG.info("Kaos {} listening on http://localhost:{} (bootstrap: {})",
            Version.read(), server.port(), options.bootstrapServer());
    }
}
