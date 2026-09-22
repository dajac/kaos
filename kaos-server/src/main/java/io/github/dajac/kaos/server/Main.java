package io.github.dajac.kaos.server;

import java.util.concurrent.Callable;

import io.github.dajac.kaos.Simulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The command line entry point. picocli parses the options, and the command starts the
 * {@link Server} with a {@link Simulator} for the given cluster. A JVM shutdown hook closes the
 * server, and with it the simulator.
 *
 * <p>Exit codes: 0 for {@code --help} and {@code --version}, 1 when the server cannot start, and 2
 * for an invalid command line. After a successful start the process keeps running until it is
 * killed.
 */
@Command(
    name = "kaos",
    description = "Interactive Kafka consumer group (KIP-848) simulator.",
    mixinStandardHelpOptions = true,
    versionProvider = Version.class,
    sortOptions = false
)
public final class Main implements Callable<Integer> {

    static final int EXIT_OK = CommandLine.ExitCode.OK;
    static final int EXIT_STARTUP_FAILURE = CommandLine.ExitCode.SOFTWARE;
    static final int EXIT_USAGE = CommandLine.ExitCode.USAGE;

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    @Mixin
    private final CliOptions options = new CliOptions();

    @Spec
    private CommandSpec spec;

    private Server server;

    Main() {}

    public static void main(String[] args) {
        Main command = new Main();
        int exitCode = new CommandLine(command).execute(args);
        if (exitCode != EXIT_OK) {
            System.exit(exitCode);
        }
        // After --help or --version there is no server, and the JVM exits on its own. The threads
        // of the simulator are daemons, so without the hook a Ctrl-C would leave the topics and the
        // groups of the simulator behind on the cluster.
        Server started = command.server();
        if (started != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(started::close, "kaos-shutdown"));
        }
    }

    /**
     * Starts the server and returns once it accepts connections.
     *
     * @return {@link #EXIT_OK}, or {@link #EXIT_STARTUP_FAILURE} if the server cannot start
     */
    @Override
    public Integer call() {
        Server candidate = new Server(new Simulator(options.bootstrapServer()));
        try {
            candidate.start(options.port());
        } catch (IllegalStateException e) {
            candidate.close();
            spec.commandLine().getErr().println(e.getMessage());
            return EXIT_STARTUP_FAILURE;
        }
        server = candidate;

        LOG.info(
            "Kaos {} listening on http://localhost:{} (bootstrap: {})",
            Version.read(), candidate.port(), options.bootstrapServer()
        );
        return EXIT_OK;
    }

    /**
     * Returns the running server, or null if the command did not start one.
     */
    Server server() {
        return server;
    }
}
