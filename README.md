# PRTS Server

A Minecraft server for **1.21.1** built on **NeoForge 21.1.250**, running Bukkit/Spigot plugins and NeoForge mods side by side.
> 中文版：[README_zh.md](README_zh.md)


## About this branch (`base/new-kernel`)

This branch carries the **next-generation PRTS server kernel — a two-axis scheduling kernel** — on top of a clean upstream base.

The kernel is built around two axes, and every part of it is **off by default**:

- **Waiting (axis A)** — a wait-point registry where each wait declares its producer, a progress reading, a timeout action and a degradation target, plus wait upper bounds that are measured rather than assumed.
- **Sharing (axis B)** — per-domain share metering, a share table and a degradation ladder, so that a domain that starts to stall is shed instead of dragging the tick down.
- **Planning and commitment** — a tick plan, a job graph with its scheduler, and a commit log whose order is the plan order.
- **Safety and observation** — a safety net and a zero-effect detector over the write path, and read-only observation keys that report what the kernel saw.

**Defaults are unchanged.** Every kernel feature sits behind a switch whose default is off; with the defaults the server behaves as the base it was built from, and each change ships with an equivalence check against the previous revision.

What the branch keeps from the base:

- a **clean upstream base** with PRTS branding and console colouring as the only functional changes;
- a **single pinned build toolchain**, so the produced bytecode is stable (the compile-time Mixin annotation processor is pinned on purpose — see `gradle/libs.versions.toml`).

Platforms: **NeoForge only** — this line supports and is verified on NeoForge alone. The `arclight-fabric` module stays in the code tree, but it is not part of the design, verification or behaviour-alignment work of this round: Fabric support is deferred until the new kernel runs on NeoForge. **Forge is not supported** on this line.

Other branches:

- `legacy/old-framework` — the previous multithreading framework line, kept for reference and history.

Status: `0.1.0-SNAPSHOT` — work in progress, no stability guarantees. The kernel is developed stage by stage; nothing is enabled by default and no production stability is claimed for it.

## Requirements

- Java 21
- A NeoForge 21.1.250 dedicated server environment (the launcher uses the matching libraries)

## Build

```bash
./gradlew collect          # full build; the server jar lands in bootstrap/build/libs/
```

Tests:

```bash
./gradlew :arclight-common:test --offline
```

## Run

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot plugins go to `plugins/`
- NeoForge mods go to `mods/`

## Links

- Repository: https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- Releases: https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## License

GPL-3.0. See `LICENSE`. Attribution and third-party notices: `NOTICE`, `THIRD-PARTY.md`, `UPSTREAM.md`.
