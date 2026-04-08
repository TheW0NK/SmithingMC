import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SmithingMC runtime prototype.
 *
 * This file intentionally keeps the initial implementation in one place to make
 * lifecycle and hot-swap behavior easy to evolve while architecture is still fluid.
 */
public final class SmithingMain {

    public static void main(String[] args) {
        RuntimeConfig config = RuntimeConfig.fromJson(SmithingConfig.DEFAULT_JSON);

        SmithingRuntime runtime = new SmithingRuntime(config);
        runtime.start();

        // Demonstrate stable hot swapping of a module in place.
        runtime.reloadModule("example:chatbridge");
        runtime.stop();
    }

    /* ------------------------------ runtime core ----------------------------- */

    static final class SmithingRuntime {
        private final RuntimeConfig config;
        private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
        private final Map<String, ManagedModule> loadedModules = new LinkedHashMap<>();
        private final Map<String, ModuleProvider> providers = new LinkedHashMap<>();

        SmithingRuntime(RuntimeConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            registerProviders(config.enabledProviders());
        }

        void start() {
            withWriteLock(() -> {
                log("Starting Smithing runtime @ " + Instant.now());
                for (ModuleSpec spec : config.modules()) {
                    loadAndEnable(spec);
                }
                log("Runtime started with " + loadedModules.size() + " module(s).");
            });
        }

        void stop() {
            withWriteLock(() -> {
                log("Stopping Smithing runtime...");
                List<ManagedModule> snapshot = new ArrayList<>(loadedModules.values());
                for (int i = snapshot.size() - 1; i >= 0; i--) {
                    ManagedModule module = snapshot.get(i);
                    safelyDisableAndUnload(module);
                }
                loadedModules.clear();
                log("Runtime stopped.");
            });
        }

        void reloadModule(String moduleId) {
            withWriteLock(() -> {
                ManagedModule current = loadedModules.get(moduleId);
                if (current == null) {
                    log("Reload requested for unknown module: " + moduleId);
                    return;
                }

                log("Hot reloading module: " + moduleId);
                ModuleSpec spec = current.spec();

                safelyDisableAndUnload(current);
                loadedModules.remove(moduleId);

                try {
                    loadAndEnable(spec);
                    log("Reload succeeded: " + moduleId);
                } catch (RuntimeException ex) {
                    log("Reload failed for " + moduleId + ": " + ex.getMessage());
                }
            });
        }

        private void registerProviders(List<String> providerKeys) {
            for (String raw : providerKeys) {
                String provider = raw.toLowerCase(Locale.ROOT);
                switch (provider) {
                    case "paper" -> providers.put(provider, new PaperProvider());
                    case "fabric" -> providers.put(provider, new FabricProvider());
                    default -> log("Ignoring unsupported provider: " + provider);
                }
            }
            if (providers.isEmpty()) {
                throw new IllegalStateException("No supported providers are enabled in configuration.");
            }
        }

        private void loadAndEnable(ModuleSpec spec) {
            ModuleProvider provider = providers.get(spec.platform());
            if (provider == null) {
                throw new IllegalStateException("No provider registered for platform: " + spec.platform());
            }

            ManagedModule module = provider.instantiate(spec);
            module.load();
            module.enable();
            loadedModules.put(spec.id(), module);
            log("Loaded module " + spec.id() + " on platform " + spec.platform());
        }

        private void safelyDisableAndUnload(ManagedModule module) {
            try {
                module.disable();
            } catch (RuntimeException ex) {
                log("Disable error for " + module.spec().id() + ": " + ex.getMessage());
            }
            try {
                module.unload();
            } catch (RuntimeException ex) {
                log("Unload error for " + module.spec().id() + ": " + ex.getMessage());
            }
        }

        private void withWriteLock(Runnable action) {
            lifecycleLock.writeLock().lock();
            try {
                action.run();
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }

        private static void log(String message) {
            System.out.println("[Smithing] " + message);
        }
    }

    /* --------------------------- provider abstractions ----------------------- */

    interface ModuleProvider {
        ManagedModule instantiate(ModuleSpec spec);
    }

    static final class PaperProvider implements ModuleProvider {
        @Override
        public ManagedModule instantiate(ModuleSpec spec) {
            return new ManagedModule(spec, "PaperAdapter");
        }
    }

    static final class FabricProvider implements ModuleProvider {
        @Override
        public ManagedModule instantiate(ModuleSpec spec) {
            return new ManagedModule(spec, "FabricAdapter");
        }
    }

    static final class ManagedModule {
        private final ModuleSpec spec;
        private final String adapter;

        ManagedModule(ModuleSpec spec, String adapter) {
            this.spec = spec;
            this.adapter = adapter;
        }

        ModuleSpec spec() {
            return spec;
        }

        void load() {
            logState("load");
        }

        void enable() {
            logState("enable");
        }

        void disable() {
            logState("disable");
        }

        void unload() {
            logState("unload");
        }

        private void logState(String phase) {
            System.out.println("[" + adapter + "] " + spec.id() + " -> " + phase);
        }
    }

    /* ---------------------------- configuration ------------------------------ */

    record RuntimeConfig(List<String> enabledProviders, List<ModuleSpec> modules) {
        static RuntimeConfig fromJson(String json) {
            // Very small parser for the fixed prototype shape to avoid external deps.
            String normalized = json.replace("\n", "").replace("\r", "").trim();

            List<String> providers = extractStringArray(normalized, "enabledProviders");
            List<ModuleSpec> modules = extractModules(normalized);

            return new RuntimeConfig(providers, modules);
        }

        private static List<String> extractStringArray(String json, String key) {
            String marker = "\"" + key + "\"";
            int keyIndex = json.indexOf(marker);
            if (keyIndex < 0) return List.of();

            int start = json.indexOf('[', keyIndex);
            int end = json.indexOf(']', start);
            if (start < 0 || end < 0 || end <= start) return List.of();

            String body = json.substring(start + 1, end).trim();
            if (body.isEmpty()) return List.of();

            String[] parts = body.split(",");
            List<String> values = new ArrayList<>();
            for (String part : parts) {
                String value = part.trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    values.add(value.substring(1, value.length() - 1));
                }
            }
            return values;
        }

        private static List<ModuleSpec> extractModules(String json) {
            String marker = "\"modules\"";
            int keyIndex = json.indexOf(marker);
            if (keyIndex < 0) return List.of();

            int start = json.indexOf('[', keyIndex);
            int end = json.indexOf(']', start);
            if (start < 0 || end < 0 || end <= start) return List.of();

            String body = json.substring(start + 1, end);
            String[] objects = body.split("\\},\\s*\\{");

            List<ModuleSpec> modules = new ArrayList<>();
            for (String obj : objects) {
                String normalized = obj.replace("{", "").replace("}", "").trim();
                if (normalized.isEmpty()) continue;

                String id = extractField(normalized, "id");
                String platform = extractField(normalized, "platform");
                String artifact = extractField(normalized, "artifact");
                if (!id.isBlank() && !platform.isBlank()) {
                    modules.add(new ModuleSpec(id, platform.toLowerCase(Locale.ROOT), artifact));
                }
            }
            return modules;
        }

        private static String extractField(String objectBody, String key) {
            String marker = "\"" + key + "\"";
            int keyIndex = objectBody.indexOf(marker);
            if (keyIndex < 0) return "";

            int colonIndex = objectBody.indexOf(':', keyIndex);
            if (colonIndex < 0) return "";

            int firstQuote = objectBody.indexOf('"', colonIndex);
            int secondQuote = objectBody.indexOf('"', firstQuote + 1);
            if (firstQuote < 0 || secondQuote < 0) return "";

            return objectBody.substring(firstQuote + 1, secondQuote);
        }
    }

    record ModuleSpec(String id, String platform, String artifact) {}

    static final class SmithingConfig {
        private SmithingConfig() {}

        static final String DEFAULT_JSON = """
            {
              "enabledProviders": ["paper", "fabric"],
              "modules": [
                {
                  "id": "example:chatbridge",
                  "platform": "paper",
                  "artifact": "plugins/chatbridge.jar"
                },
                {
                  "id": "example:worldsync",
                  "platform": "fabric",
                  "artifact": "mods/worldsync.jar"
                }
              ]
            }
            """;
    }
}
