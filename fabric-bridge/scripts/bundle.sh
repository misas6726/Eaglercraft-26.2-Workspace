#!/usr/bin/env bash
# Run the bridge over a folder of Fabric mod jars and the compiled game classes.
#
#   ASM_CP=asm.jar:asm-tree.jar:asm-commons.jar ./fabric-bridge/scripts/bundle.sh mods/ game/build/classes/java/main build/fabric-bridge
#
# Output: <out>/classes (put FIRST on the TeaVM program classpath), <out>/resources, <out>/report.txt
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MODS="${1:?usage: bundle.sh <mods dir> <compiled game classes dir> <out dir> [--strict]}"
CLASSES="${2:?missing compiled game classes dir}"
OUT="${3:?missing output dir}"
shift 3 || true
ASM_CP="${ASM_CP:?set ASM_CP to asm, asm-tree and asm-commons jars (9.8+ reads Java 24 class files)}"
TOOL="$OUT/.tool"
mkdir -p "$TOOL"
javac --release 17 -nowarn -d "$TOOL" -cp "$ASM_CP" $(find "$HERE/tool/src" -name '*.java')
# The runtime shims (FabricLoader, CallbackInfo, ...) must ship with the client too.
javac --release 17 -nowarn -d "$OUT/runtime-classes" $(find "$HERE/runtime/src" -name '*.java')
java -cp "$TOOL:$ASM_CP" dev.frozenclient.fabricbridge.Main --mods "$MODS" --classes "$CLASSES" --out "$OUT" "$@"
echo "also add $OUT/runtime-classes to the program classpath"
