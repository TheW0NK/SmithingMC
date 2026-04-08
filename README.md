# SmithingMC

SmithingMC is an experimental Minecraft server runtime focused on one goal:

> Run **Paper plugins** and **Fabric mods** side by side with stable hot swapping.

## Current state

This repository currently contains a lightweight Java prototype that models the core runtime behavior SmithingMC will need:

- A single runtime that can host both plugin/mod providers.
- A hot-swap lifecycle (`load`, `enable`, `disable`, `unload`) that can be run safely.
- Provider adapters for `paper` and `fabric` namespaces.
- JSON configuration for selecting providers and declaring modules.

The implementation is intentionally minimal and dependency-free so the lifecycle and orchestration patterns can be refined before deeper game integration.

## Prototype module lifecycle

Each module goes through:

1. `load()`
2. `enable()`
3. live operation
4. `disable()`
5. `unload()`

Hot reload is implemented as `disable -> unload -> load -> enable` under a runtime write lock.

## Run

```bash
javac SmithingMain.java
java SmithingMain
```

## Next milestones

- Replace stub provider adapters with real Paper/Fabric bridging layers.
- Add classloader isolation per module to support real hot swapping.
- Add dependency graph ordering between modules.
- Add robust failure rollback during partial reload.
- Add integration test harness for mixed Paper/Fabric module sets.
