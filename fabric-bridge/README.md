# Fabric bridge for Frozen Client (Eaglercraft 26.2)

Turns Fabric mod jars into code that is compiled **into** the client at build time.

Eaglercraft is compiled ahead of time to WebAssembly by TeaVM, so there is no runtime class loading in the
browser. A drop-in "Fabric Loader" is therefore impossible. This tool does the work Fabric Loader and Mixin
normally do at launch, but offline, on the compiled game classes, before the TeaVM link step.

## What it does

1. Reads every `*.jar` in a mods folder (`fabric.mod.json`, entrypoints, mixin configs).
2. Applies the supported Mixin subset to the compiled game classes (see below).
3. Generates `FabricBridgeBootstrap` and hooks it into the game:
   - `main` entrypoints (`ModInitializer`) run at the start of `BuiltInRegistries.freeze()`: vanilla content
     exists, registries are not frozen yet. This runs in every isolate that bootstraps registries (client,
     singleplayer server worker, mesh workers), so mod blocks exist everywhere.
   - `client` entrypoints (`ClientModInitializer`) run once at the start of `Minecraft.run()`.
4. Copies mod classes (minus mixin classes, which are merged away) and `assets/` + `data/` resources.
5. Scans every mod for things that cannot work here and writes `report.txt`.

## Usage

```sh
ASM_CP=/path/asm-9.8.jar:/path/asm-tree-9.8.jar:/path/asm-commons-9.8.jar \
  fabric-bridge/scripts/bundle.sh  mods/  game/build/classes/java/main  build/fabric-bridge  [--strict]
```

`--strict` exits non-zero if anything was skipped or flagged. Hook points can be changed with
`--hook-init Class.method()V` and `--hook-client Class.method()V`.

Run the self-test (compiles a demo mod, applies it, executes the result with bytecode verification):

```sh
ASM_CP=... fabric-bridge/scripts/test.sh
```

## Supported Mixin subset

| Feature | Notes |
| --- | --- |
| `@Mixin` | class and string `targets`; several mixins per target apply in jar-name order |
| `@Shadow` | fields and methods, no `prefix` |
| `@Unique` / plain members | merged into the target; renamed if they clash with a target method |
| `@Overwrite` | replaces the target method |
| `@Inject` | `at` = `HEAD`, `RETURN`, `TAIL`; `cancellable`; `CallbackInfo` and `CallbackInfoReturnable`; static and instance handlers; the handler must take exactly the target's arguments plus the callback info |
| `@Accessor` / `@Invoker` | instance fields and methods; interface is added to the target class |

Anything else is **reported as skipped, never silently ignored**: other `@At` kinds (`INVOKE`, `FIELD`, ...),
`@Redirect`, `@ModifyArg`/`@ModifyVariable`/`@ModifyConstant`, captured locals, injecting into constructors,
static accessors, interface targets, `@Shadow(prefix=...)`, mixin plugins.

## Limits that are not fixable by this tool

- **Fabric API is not provided.** Mods that use `net.fabricmc.fabric.*` (events, registries helpers, rendering
  API, ...) are flagged and will not link. Only `ModInitializer`, `ClientModInitializer`, `EnvType`,
  `Environment` and a small `FabricLoader` (mod list, environment) exist.
- **Names must match this client.** 26.x is unobfuscated, so mods built for 26.x use the same names as this
  source, but any mod built for older versions (intermediary / Yarn names) will not match. The report lists
  game classes and members a mod references that do not exist here.
- **Browser constraints.** Threads, sockets, files, and reflection are unavailable or limited in the TeaVM
  build. Mods touching them are flagged.
- **Mod jars are build inputs.** The browser cannot load a mod at runtime; you rebuild the client to add or
  remove one.

## Not done / not verified

- The tool is tested on the JVM against stub game classes, **not** against the real client build. The real
  game classes are Java 24 bytecode: use ASM 9.8 or newer.
- **Build integration is manual.** The transformed `classes` folder (and `runtime-classes`) must be placed
  first on the TeaVM program classpath of each target: client (`target_teavm_wasm_gc`), singleplayer server
  (`target_teavm_wasm_gc_server`) and mesh worker (`target_teavm_wasm_gc_mesh`). Wiring that into
  `wasm-toolchain` / Gradle has not been done or tested.
- Mod `assets/` and `data/` end up in `resources/`; getting them into the packaged client (resource overlay)
  is also manual.

## Layout

- `tool/` the bundler (ASM based: Mixin applier, compat scanner, bootstrap generator)
- `runtime/` shim classes that ship with the client (`net.fabricmc.api.*`, `FabricLoader`, `CallbackInfo*`)
- `test/` stub game classes, a demo mod, and the verification runner
- `scripts/` `bundle.sh`, `test.sh`
