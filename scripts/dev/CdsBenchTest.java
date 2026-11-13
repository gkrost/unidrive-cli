import java.nio.file.*;
import java.util.List;

public class CdsBenchTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("cds-bench-test");
        Path flags = dir.resolve("flags");
        Files.writeString(flags, "  # comment\n\n-Xms64m # heap\n-Xmx128m\n");
        if (!CdsBench.readFlags(flags).equals(List.of("-Xms64m", "-Xmx128m"))) throw new AssertionError("flags");
        Path log = CdsBench.applicationLog(dir);
        if (!log.equals(dir.resolve("data/unidrive/unidrive.log"))) throw new AssertionError("log path");
        if (CdsBench.ready(log)) throw new AssertionError("missing log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "INFO IPC: transport pool size=2\n");
        if (!CdsBench.ready(log)) throw new AssertionError("application log readiness");
    }
}
