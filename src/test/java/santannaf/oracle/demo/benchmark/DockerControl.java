package santannaf.oracle.demo.benchmark;

/**
 * Utility for controlling Docker containers during benchmark tests.
 *
 * Usa 'docker stop --time 1' em vez de 'docker pause' para failover tests.
 * docker pause congela o processo mas mantém TCP vivo — nenhum pool detecta a falha rapidamente.
 * docker stop mata o processo e fecha TCP (RST) — o pool detecta conexão quebrada imediatamente.
 */
public final class DockerControl {

    public static final String NODE1 = "oracle-node1";
    public static final String NODE2 = "oracle-node2";

    private DockerControl() {}

    public static void pause(String container) throws Exception {
        exec("pause", container);
    }

    public static void unpause(String container) throws Exception {
        exec("unpause", container);
    }

    public static void stop(String container) throws Exception {
        exec("stop", "--time", "1", container);
    }

    public static void start(String container) throws Exception {
        exec("start", container);
    }

    private static void exec(String... args) throws Exception {
        var cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        var process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("docker " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }
}
