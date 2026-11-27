package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;
import cn.mits.nekogate.api.NekoGateExtension;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

final class NekoGateExtensionLoader implements AutoCloseable {
    private final List<NekoGateExtension> loaded = new ArrayList<>();
    private URLClassLoader classLoader;

    void load(Path directory, NekoGateAPI api) {
        try {
            Files.createDirectories(directory);
            List<URL> jars;
            try (var paths = Files.list(directory)) {
                jars = paths.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(path -> {
                            try { return path.toUri().toURL(); }
                            catch (IOException e) { throw new IllegalStateException(e); }
                        }).toList();
            }
            if (jars.isEmpty()) {
                System.out.println("[NekoGate] No Java extensions found in " + directory);
                return;
            }

            this.classLoader = new URLClassLoader(jars.toArray(URL[]::new), NekoGateExtension.class.getClassLoader());
            for (NekoGateExtension extension : ServiceLoader.load(NekoGateExtension.class, this.classLoader)) {
                try {
                    extension.onEnable(api);
                    this.loaded.add(extension);
                    System.out.println("[NekoGate] Enabled extension: " + extension.getClass().getName());
                } catch (Throwable error) {
                    System.err.println("[NekoGate] Extension failed to enable (" +
                            extension.getClass().getName() + "): " + error);
                }
            }
        } catch (Throwable error) {
            System.err.println("[NekoGate] Could not load Java extensions: " + error);
        }
    }

    @Override
    public void close() {
        for (int i = this.loaded.size() - 1; i >= 0; i--) {
            NekoGateExtension extension = this.loaded.get(i);
            try { extension.onDisable(); }
            catch (Throwable error) {
                System.err.println("[NekoGate] Extension failed to disable (" +
                        extension.getClass().getName() + "): " + error);
            }
        }
        this.loaded.clear();
        if (this.classLoader != null) {
            try { this.classLoader.close(); }
            catch (IOException error) { System.err.println("[NekoGate] Could not close extension classloader: " + error); }
            this.classLoader = null;
        }
    }
}
