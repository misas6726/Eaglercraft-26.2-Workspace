#!/usr/bin/env bash
# Builds the bridge tool, compiles a demo Fabric mod against stub game classes, runs the tool on it,
# then executes the transformed classes on the JVM to check every supported Mixin feature.
#
#   ASM_CP=/path/asm.jar:/path/asm-tree.jar:/path/asm-commons.jar ./scripts/test.sh [workdir]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$(mktemp -d)}"
ASM_CP="${ASM_CP:?set ASM_CP to asm, asm-tree and asm-commons jars (9.8+ for Java 24 class files)}"
REL="${JAVA_RELEASE:-21}"
JC="javac --release $REL -nowarn -proc:none"
rm -rf "$WORK" && mkdir -p "$WORK"/{runtime,stubs,game,mod,tool,mods,out,runner}

$JC -d "$WORK/tool" -cp "$ASM_CP" $(find "$HERE/tool/src" -name '*.java')
$JC -d "$WORK/runtime" $(find "$HERE/runtime/src" -name '*.java')
$JC -d "$WORK/stubs" -cp "$WORK/runtime" $(find "$HERE/test/src/stubs" -name '*.java')
$JC -d "$WORK/game" $(find "$HERE/test/src/game" -name '*.java')
$JC -d "$WORK/mod" -cp "$WORK/runtime:$WORK/stubs" $(find "$HERE/test/src/mod" -name '*.java')

# Package the demo mod (without BadMod, which is a separate jar for the compat scan).
python3 - "$WORK" "$HERE" <<'PY'
import sys, zipfile, os
work, here = sys.argv[1], sys.argv[2]
def jar(path, files):
    with zipfile.ZipFile(path, "w") as z:
        for arc, data in files:
            z.writestr(arc, data)
mod = os.path.join(work, "mod")
def cls(name):
    p = os.path.join(mod, name)
    return (name, open(p, "rb").read())
demo = [cls("demo/mod/DemoMod.class"), cls("demo/mod/DemoClient.class"),
        cls("demo/mod/mixin/TargetMixin.class"), cls("demo/mod/mixin/TargetAccessor.class"),
        ("fabric.mod.json", '{"schemaVersion":1,"id":"demo","version":"1.0.0","entrypoints":{"main":["demo.mod.DemoMod"],"client":["demo.mod.DemoClient"]},"mixins":["demo.mixins.json"]}'),
        ("demo.mixins.json", '{"required":true,"package":"demo.mod.mixin","mixins":["TargetMixin","TargetAccessor"],"client":[]}'),
        ("assets/demo/lang/en_us.json", '{"demo.hello":"Hello"}')]
jar(os.path.join(work, "mods", "demo-1.0.0.jar"), demo)
bad = [cls("demo/mod/BadMod.class"),
       ("fabric.mod.json", '{"schemaVersion":1,"id":"badmod","version":"0.1","entrypoints":{},"mixins":[]}')]
jar(os.path.join(work, "mods", "badmod-0.1.jar"), bad)
PY

java -cp "$WORK/tool:$ASM_CP" dev.frozenclient.fabricbridge.Main \
  --mods "$WORK/mods" --classes "$WORK/game" --out "$WORK/out" \
  --hook-init net/minecraft/demo/Game.boot\(\)V --hook-client net/minecraft/demo/Game.run\(\)V | tee "$WORK/tool.log"

$JC -d "$WORK/runner" -cp "$WORK/runtime:$WORK/game:$WORK/mod" "$HERE/test/src/runner/BridgeTest.java"
# Transformed classes first; the stubs and the raw mod classes are NOT on the runtime classpath.
java -Xverify:all -cp "$WORK/out/classes:$WORK/game:$WORK/runtime:$WORK/runner" BridgeTest
