# Resource use guide

Reference numbers for sizing LitematicaFolia paste operations. Empirically measured on Luminol 26.1.2 + JDK 25 (`-XX:+UseZGC -Xmx4G`).

## Memory model

`PasteOperation.execute()` keeps the **entire** parsed schematic in memory for the lifetime of the paste:

- `LitematicSchematic` palette (deduplicated `BlockStateEntry` list)
- `LitematicRegion.blocks[]` — a flat `int[]` of size `sizeX * sizeY * sizeZ`
- Tile-entity / entity / pending-tick `NbtList`s

There is no streaming mode. For a 256×64×256 schematic that is ≈4.2 M `int` cells ≈ **17 MiB** just for the block grid (palette indices fit in ints), plus palette + TE NBT.

## Disk size guidance (gzip after write)

| Schematic | Cells | Palette | Gzip on disk | Plugin RAM (during paste) |
| --- | ---: | ---: | ---: | ---: |
| 4×4×4 stone-cube | 64 | 2 | ~340 B | trivial |
| 8×8×8 mixed room | 512 | 6 | ~640 B | trivial |
| 32×32×32 cube | 32 768 | 4 | ~80 KiB | ~150 KiB |
| 128×64×128 | ~1 M | ~32 | ~2 MiB | ~5 MiB |
| **256×64×256 stress** | ~4.2 M | 11 | **~3 MiB** (≤ 16 MiB upper bound) | ~25 MiB on heap + spike ≈ +200 MiB server RSS during chunk writes |
| 512×384×512 | ~100 M | varies | 50–200 MiB | **paste not recommended without `--stream` (not yet implemented)** |

## Recommendations

- For schematics > 100 M cells, slice into smaller `.litematic` files at export time (Litematica supports multi-region; load each region separately).
- The Folia smoke baseline (`test-harness/run-tests.sh`) runs with `-Xmx2G`. The stress smoke harness bumps to `-Xmx4G` because chunk pre-generation around `(100, 64, 100)` plus the 256-block paste budget needs more than 1 G of headroom.
- The cancellation flag (`/litematica cancel`) is the only safe way to bail out of a runaway paste. `kill -9` on the JVM during a paste can leave region files in an inconsistent state — Folia's `RegionShutdownThread` requires a graceful `stop` (or, on production, `save-all flush` → `stop`).

## TODOs for future versions

- `--stream` flag: read+place region-by-region, freeing memory as each region completes. Useful for 100 M+ cell schematics.
- Per-chunk task budget: the current behaviour places all of a chunk's blocks in one region task. For very dense schematics this can be visible in MSPT. The `PasteOptions.maxBlocksPerChunkTask` field exists but is currently advisory.
- Memory pressure short-circuit: poll `Runtime.getRuntime().freeMemory()` between chunk batches and pause if heap drops below a watermark.
