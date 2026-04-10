package io.github.dajac.kaos;

public class KaosApp {

    public static void main(String[] args) throws Exception {
        String bootstrapServers = null;
        int port = 8081;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bootstrap-server":
                    if (i + 1 < args.length) bootstrapServers = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        if (bootstrapServers == null) {
            System.err.println("Error: --bootstrap-server is required");
            printUsage();
            System.exit(1);
        }

        SimulatorEngine engine = new SimulatorEngine(bootstrapServers);
        SimulatorRestServer restServer = new SimulatorRestServer(port, engine);
        restServer.start();

        System.out.println("Kaos running at http://localhost:" + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            engine.stop();
            restServer.stop();
        }));

        // Block main thread.
        Thread.currentThread().join();
    }

    private static void printUsage() {
        System.err.println("Usage: kaos --bootstrap-server <servers> [--port <port>]");
        System.err.println("  --bootstrap-server  Kafka bootstrap servers (required)");
        System.err.println("  --port              HTTP server port (default: 8081)");
    }
}
