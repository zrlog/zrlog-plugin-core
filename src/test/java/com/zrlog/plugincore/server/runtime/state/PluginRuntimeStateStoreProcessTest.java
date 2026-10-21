package com.zrlog.plugincore.server.runtime.state;

import com.hibegin.common.dao.InMemoryDatabase;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PluginRuntimeStateStoreProcessTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 60000)
    public void shouldKeepBothRuntimesStatesWhenSeparateProcessesReadTheSameVersion() throws Exception {
        Path root = temporary.getRoot().toPath();
        List<Process> processes = new ArrayList<>();
        try (InMemoryDatabase database = open(root)) {
            database.update("CREATE TABLE website (name TEXT PRIMARY KEY, value TEXT, remark TEXT)");
            database.update("INSERT INTO website(name,value) VALUES (?,?)",
                    "distributed_lock_plugin_runtime_states", "orphan|" + (System.currentTimeMillis() + 300000L));
            for (String writer : new String[]{"runtime-a", "runtime-b"}) {
                processes.add(new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Writer.class.getName(), root.toString(), writer)
                        .redirectErrorStream(true).redirectOutput(root.resolve(writer + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(root.resolve("runtime-a.ready")) || !Files.exists(root.resolve("runtime-b.ready"))) {
                assertTrue("writers must read the initial snapshot before the deadline", System.nanoTime() < deadline);
                for (Process process : processes) {
                    assertTrue("writer exited before synchronization; logs: " + root, process.isAlive());
                }
                Thread.sleep(10L);
            }
            Files.createFile(root.resolve("go"));
            for (int i = 0; i < processes.size(); i++) {
                Process process = processes.get(i);
                assertTrue("writer must complete", process.waitFor(20, TimeUnit.SECONDS));
                String writer = i == 0 ? "runtime-a" : "runtime-b";
                assertEquals(Files.readString(root.resolve(writer + ".log")), 0, process.exitValue());
            }

            PluginRuntimeStateStore store = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());
            assertEquals(16, store.list().size());
            for (String writer : new String[]{"runtime-a", "runtime-b"}) {
                for (int i = 0; i < 8; i++) {
                    assertEquals("ready", store.find(writer + "-plugin-" + i).get().getStatus());
                }
            }
        } finally {
            for (Process process : processes) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue("test writer must be reaped", process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static InMemoryDatabase open(Path root) {
        Properties properties = new Properties();
        properties.setProperty("driverClass", "org.sqlite.JDBC");
        properties.setProperty("jdbcUrl", "jdbc:sqlite:" + root.resolve("shared.db")
                + "?journal_mode=WAL&busy_timeout=10000");
        properties.setProperty("user", "");
        properties.setProperty("password", "");
        return InMemoryDatabase.open(properties, true);
    }

    public static class Writer {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            String writer = args[1];
            try (InMemoryDatabase ignored = open(root)) {
                WebsiteRuntimeKvStore repository = new WebsiteRuntimeKvStore() {
                    private boolean firstRead = true;

                    @Override
                    public Optional<String> get(String key) {
                        Optional<String> value = super.get(key);
                        if (firstRead) {
                            firstRead = false;
                            try {
                                Files.createFile(root.resolve(writer + ".ready"));
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                                while (!Files.exists(root.resolve("go"))) {
                                    if (System.nanoTime() >= deadline) {
                                        throw new IllegalStateException("writer barrier timed out");
                                    }
                                    Thread.sleep(10L);
                                }
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        return value;
                    }
                };
                PluginRuntimeStateStore store = new PluginRuntimeStateStore(repository);
                for (int i = 0; i < 8; i++) {
                    store.update(writer + "-plugin-" + i, state -> state.setStatus("ready"));
                }
            }
        }
    }
}
