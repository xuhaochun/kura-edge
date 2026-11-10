#!/usr/bin/env bash
# kura-edge 桌面版一键打包。
#
# 用法：build-desktop.sh [--version <版本>] [--jar-only]
#
#   --version <版本>   把版本号写进四处（tauri.conf.json / package.json / src-tauri/Cargo.toml /
#                      update-core/Cargo.toml）并用它命名全部产物；不传则沿用现值。
#                      例：--version 1.0.4 → kura-edge-1.0.4.jar、kura-edge_1.0.4_x64-setup.exe
#   --jar-only         只出热更新 jar，不跑 Tauri/NSIS
#
# 两种模式：
#   完整模式（默认）    前端 dist → 后端 fat jar → 内嵌 JRE → Tauri 交叉编译 → NSIS setup.exe
#                       产物：desktop/dist/kura-edge_<版本>_x64-setup.exe
#                       （exe/JRE/前端 有任何变化都必须走这条）
#   --jar-only          前端 dist → 后端 fat jar → 发布可热更新的 jar
#                       产物：desktop/dist/kura-edge-<版本>.jar + .sha256 + feed.json
#                       （只改了后端/前端、exe 外壳不动时走这条：设备用 /api/v1/system/update
#                        换 jar 即可，不用重装 exe。RFC 006）
#
# 前提（本机 Linux，无 sudo 的用户态工具链）：
#   ~/.cargo                      rustup stable + x86_64-pc-windows-msvc + cargo-xwin
#   ~/tools/tauri-cross/bin       lld-link / llvm-rc（从 apt 包解压后软链）
#   ~/tools/tauri-cross/nsis-linux/usr/bin/makensis（从 apt 包解压）
#   ~/nodejs/bin                  node / npm
# 路径可用 KURA_* 环境变量覆盖；详见 desktop/README.md。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # desktop/
REPO="$(cd "$ROOT/.." && pwd)"                            # 仓库根
TARGET="${TAURI_TARGET:-x86_64-pc-windows-msvc}"
OUT="$ROOT/dist"

CROSS_HOME="${KURA_CROSS_HOME:-$HOME/tools/tauri-cross}"
NODE_BIN_DIR="${KURA_NODE_BIN_DIR:-$HOME/nodejs/bin}"
CARGO_BIN_DIR="${KURA_CARGO_BIN_DIR:-$HOME/.cargo/bin}"
MVN_BIN="${KURA_MVN:-$HOME/tools/apache-maven-3.9.11/bin/mvn}"
NSIS_SHARE="${KURA_NSIS_DIR:-$CROSS_HOME/nsis-linux/usr/share/nsis}"

MODE=full
ARG_VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --jar-only) MODE=jar ;;
        --version|-V)
            [ $# -ge 2 ] || { echo "--version 需要一个版本号，例如 --version 1.0.4" >&2; exit 2; }
            ARG_VERSION="$2"
            shift
            ;;
        --version=*)
            ARG_VERSION="${1#--version=}"
            [ -n "$ARG_VERSION" ] || { echo "--version= 后面没跟版本号，例如 --version=1.0.4" >&2; exit 2; }
            ;;
        -h|--help)
            # 打印文件头的注释块（到第一行非注释为止），避免帮助文本和正文两处漂移
            awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        *)
            echo "未知参数：$1（可用：--version <版本>、--jar-only、--help）" >&2
            exit 2
            ;;
    esac
    shift
done

# 版本号是热更新的身份：文件名、pending.json 的 version、current.json 的 version 全都用它，
# 四处必须一致，否则外壳会以 "同一版本不同内容" 拒装（update-core 的 version_conflict）。
conf_version() { sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -1; }
toml_version() { sed -n 's/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -1; }

# 版本号来源：--version 参数优先，否则沿用 tauri.conf.json 的现值（老用法不变）。
VERSION="${ARG_VERSION:-$(conf_version "$ROOT/src-tauri/tauri.conf.json")}"
[ -n "$VERSION" ] || { echo "无法从 tauri.conf.json 读出 version" >&2; exit 1; }

# 与后端校验同一套规则（kura-edge-rest 的 UpdateRequestDto / update-core 的 check_version）
if ! [[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$ ]]; then
    echo "版本号 '$VERSION' 不合法：只能 [0-9A-Za-z._-]，首字符须为字母或数字，最长 32" >&2
    exit 1
fi

# --version 的值会写进 tauri.conf.json，而 Tauri 要求那里是 semver（不合规会在 cargo 阶段才炸），
# 显式传版本号时先拦下来，别等 NSIS 跑一半才发现。
if [ -n "$ARG_VERSION" ] && ! [[ "$ARG_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$ ]]; then
    echo "版本号 '$ARG_VERSION' 不是 semver：Tauri 要求 MAJOR.MINOR.PATCH（可带 -pre / +build），例如 1.0.4" >&2
    exit 1
fi

# 每处只改第一处匹配：tauri.conf.json 顶层只有一个 "version"，但嵌套对象以后可能加；
# Cargo.toml 里依赖也写 version = "2"，锚到行首 ^version 才不会误伤。
write_version_json() {
    sed -i "0,/^[[:space:]]*\"version\"[[:space:]]*:/s/^\([[:space:]]*\)\"version\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/\1\"version\": \"$2\"/" "$1"
}
write_version_toml() {
    sed -i "0,/^version[[:space:]]*=[[:space:]]*\"/s/^\(version[[:space:]]*=[[:space:]]*\)\"[^\"]*\"/\1\"$2\"/" "$1"
}

if [ -n "$ARG_VERSION" ]; then
    OLD_VERSION="$(conf_version "$ROOT/src-tauri/tauri.conf.json")"
    if [ "$ARG_VERSION" = "$OLD_VERSION" ]; then
        echo "[version] $ARG_VERSION 与现值相同：只重新打包，不改文件"
    else
        write_version_json "$ROOT/src-tauri/tauri.conf.json" "$ARG_VERSION"
        write_version_json "$ROOT/package.json" "$ARG_VERSION"
        write_version_toml "$ROOT/src-tauri/Cargo.toml" "$ARG_VERSION"
        write_version_toml "$ROOT/update-core/Cargo.toml" "$ARG_VERSION"
        # 当场自检：sed 没匹配上会静默不生效，靠下面的"四处一致"校验只会报得更难懂
        write_failed=""
        [ "$(conf_version "$ROOT/src-tauri/tauri.conf.json")" = "$ARG_VERSION" ] || write_failed="$ROOT/src-tauri/tauri.conf.json"
        [ "$(conf_version "$ROOT/package.json")" = "$ARG_VERSION" ] || write_failed="$ROOT/package.json"
        [ "$(toml_version "$ROOT/src-tauri/Cargo.toml")" = "$ARG_VERSION" ] || write_failed="$ROOT/src-tauri/Cargo.toml"
        [ "$(toml_version "$ROOT/update-core/Cargo.toml")" = "$ARG_VERSION" ] || write_failed="$ROOT/update-core/Cargo.toml"
        if [ -n "$write_failed" ]; then
            echo "写入版本号失败：$write_failed（sed 没匹配到 version 字段，检查该文件格式）" >&2
            exit 1
        fi
        echo "[version] $OLD_VERSION → $ARG_VERSION（已写入 tauri.conf.json / package.json / src-tauri/Cargo.toml / update-core/Cargo.toml）"

        # Cargo.lock 里两个本地 crate 的版本行也得跟着走，否则 cargo 认为 lock 与清单不一致。
        # cargo update -p <本地包> --offline：只碰这两个包，不联网、不动第三方依赖版本。
        (cd "$ROOT/update-core" && "$CARGO_BIN_DIR/cargo" update --offline -p kura-edge-update-core > /dev/null)
        (cd "$ROOT/src-tauri" && "$CARGO_BIN_DIR/cargo" update --offline -p kura-edge-desktop > /dev/null)
        echo "[version] Cargo.lock 已同步（update-core / src-tauri）"

        # 设备端自动更新只升不降（update-core 的更新决策），降版本包发过去会被静默忽略
        if [ "$(printf '%s\n%s\n' "$OLD_VERSION" "$ARG_VERSION" | sort -V | head -1)" = "$ARG_VERSION" ]; then
            echo "[version] 注意：$ARG_VERSION 低于现值 $OLD_VERSION；自动更新只升不降，这个包只适合重装或回滚验证" >&2
        fi
    fi
fi

for pair in "desktop/package.json:$(conf_version "$ROOT/package.json")" \
            "desktop/src-tauri/Cargo.toml:$(toml_version "$ROOT/src-tauri/Cargo.toml")" \
            "desktop/update-core/Cargo.toml:$(toml_version "$ROOT/update-core/Cargo.toml")"; do
    file="${pair%%:*}"
    value="${pair##*:}"
    if [ "$value" != "$VERSION" ]; then
        cat >&2 <<MESSAGE
版本号不一致：tauri.conf.json 是 $VERSION，而 $file 是 '${value:-（空）}'。
热更新靠版本号判定"该不该装"，四处必须同步改：
  desktop/src-tauri/tauri.conf.json   "version"
  desktop/package.json                "version"
  desktop/src-tauri/Cargo.toml        version
  desktop/update-core/Cargo.toml      version
MESSAGE
        exit 1
    fi
done

if [ "$MODE" = jar ]; then
    TOTAL=4
else
    TOTAL=9
fi
STEP=0
step() {
    STEP=$((STEP + 1))
    printf '\n=== %s/%s %s ===\n' "$STEP" "$TOTAL" "$*"
}

mkdir -p "$OUT"

if [ "$MODE" = full ]; then
    # Tauri 启动 makensis 时不传 NSISDIR，用包装器在 exec 前补上（否则读 /usr/share/nsis 的 stub 而失败）
    NSIS_WRAPPER_DIR="$OUT/.bin"
    mkdir -p "$NSIS_WRAPPER_DIR"
    cat > "$NSIS_WRAPPER_DIR/makensis" <<WRAPPER
#!/usr/bin/env bash
export NSISDIR="\${NSISDIR:-$NSIS_SHARE}"
exec "$CROSS_HOME/nsis-linux/usr/bin/makensis" "\$@"
WRAPPER
    chmod +x "$NSIS_WRAPPER_DIR/makensis"
    export NSISDIR="$NSIS_SHARE"

    export PATH="$NSIS_WRAPPER_DIR:$CARGO_BIN_DIR:$CROSS_HOME/bin:$NODE_BIN_DIR:$PATH"
else
    export PATH="$CARGO_BIN_DIR:$NODE_BIN_DIR:$PATH"
fi
export LD_LIBRARY_PATH="$CROSS_HOME/llvm/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export XWIN_CACHE_DIR="${XWIN_CACHE_DIR:-$HOME/.cache/cargo-xwin}"

echo "版本：$VERSION　模式：$([ "$MODE" = jar ] && echo '仅热更新 jar' || echo '完整安装包')"

step "前端构建（frontend/dist）"
# 显式传空凭据：抵消 frontend/.env.local 里的开发口令，桌面包改用外壳每次启动注入的随机口令
(cd "$REPO/frontend" && VITE_WIRES_USERNAME= VITE_WIRES_PASSWORD= npm run build)

step "热更新纯逻辑测试（desktop/update-core）"
# 解析清单 / 校验 jar / 原子写 / 更新决策这些纯逻辑在单独的 crate 里，能在本机（无 webkit2gtk）真跑；
# 它挂了就不该继续打包——装到设备上才发现换 jar 换坏了代价太大（RFC 006）。
(cd "$ROOT/update-core" && "$CARGO_BIN_DIR/cargo" test --offline)

step "后端 fat jar（-Pdesktop：dist 拷进 jar 的 static/）"
# frontend/dist 缺失时 copy-resources 只告警不失败，会产出没有 static/ 的 jar，先 fail-fast
[ -f "$REPO/frontend/dist/index.html" ] || { echo "缺少 frontend/dist/index.html：上一步前端构建没产出" >&2; exit 1; }
# 打包前先跑一次 Spring 上下文启动测试：配置写坏（例如 application.yml 里 H2 参数多一个空格）
# 会当场失败，而不是等用户装完 exe 才发现后端起不来。桌面包用 -DskipTests，所以这一步必须单独跑。
(cd "$REPO" && "$MVN_BIN" -B -o -pl kura-edge-rest -am test \
    -Dtest=KuraEdgeApplicationContextTest -Dsurefire.failIfNoSpecifiedTests=false)
(cd "$REPO" && "$MVN_BIN" -B -Pdesktop -pl kura-edge-rest -am -DskipTests package)

# 按 mtime 取最新产出的 jar：target/ 会保留历史版本的 jar，find 的自然顺序不保证最新
JAR="$(find "$REPO/kura-edge-rest/target" -maxdepth 1 -name '*.jar' ! -name '*original*' ! -name '*sources*' \
    -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -1 | cut -f2- || true)"
[ -n "$JAR" ] || { echo "未找到后端 jar" >&2; exit 1; }
echo "[jar] $JAR ($(du -h "$JAR" | cut -f1))"

step "发布可热更新的 jar（dist/kura-edge-$VERSION.jar + .sha256 + feed.json）"
# 与 exe 里带的那份是同一个构建产物（同一个 -Pdesktop 输出），所以热更新的 jar 必然自带匹配的前端。
# 文件名必须与 update-core 的 jar_file_name() 一致：kura-edge-<version>.jar，否则外壳按 invalid_jar_name 拒收。
HOT_JAR="$OUT/kura-edge-$VERSION.jar"
cp -f "$JAR" "$HOT_JAR"
HOT_SHA="$(sha256sum "$HOT_JAR" | cut -d' ' -f1)"
HOT_SIZE="$(stat -c %s "$HOT_JAR")"
printf '%s  %s\n' "$HOT_SHA" "kura-edge-$VERSION.jar" > "$HOT_JAR.sha256"

# 更新清单（RFC 008）：设备定时拉这个 feed.json，发现更新的版本就自己走蓝绿升级。
# url 用相对路径——把 feed.json 与 jar 放进同一个目录上传，设备按清单自身地址解析。
# 顺手投放给发布服务器（RFC 009）：kura-edge-release-server 会自己扫这个目录生成 feed.json，
# 设备只需把 KURA_EDGE_UPDATE_FEED 指到 http://<服务器>:8081/feed.json。
RELEASE_DIR="${KURA_RELEASE_DIR:-$REPO/kura-edge-release-server/releases}"
mkdir -p "$RELEASE_DIR"
cp -f "$HOT_JAR" "$RELEASE_DIR/"
echo "[release] 已投放 $RELEASE_DIR/$(basename "$HOT_JAR")"

cat > "$OUT/feed.json" <<FEED
{
  "protocol": 1,
  "latest": {
    "version": "$VERSION",
    "url": "kura-edge-$VERSION.jar",
    "sha256": "$HOT_SHA",
    "size": $HOT_SIZE,
    "publishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  }
}
FEED

if [ "$MODE" = jar ]; then
    printf '\n完成（仅热更新）：%s (%s)\n' "$HOT_JAR" "$(du -h "$HOT_JAR" | cut -f1)"
    printf 'sha256：%s\n' "$HOT_SHA"
    cat <<HINT

自动更新（推荐，要求上位机能单向访问服务器）——两种发法，任选其一：

【A】用本项目的发布服务器（最简单的现场形态）
  1) 服务器上起 8081：cd kura-edge-release-server && java -jar target/kura-edge-release-server-*.jar
     （jar 已经自动投放到 kura-edge-release-server/releases/ 了，清单由它自己生成）
  2) 设备上只配一个地址（清单路径由它推导）：KURA_RELEASE_SERVER_URL=http://<服务器IP>:8081

【B】自己拿 nginx/静态目录发
  把这两个文件放进服务器的同一目录：
  kura-edge-$VERSION.jar
  feed.json        ← 已经生成好，url 是相对路径
然后给设备配上清单地址（系统环境变量或外壳启动环境）：
  KURA_EDGE_UPDATE_FEED=http://<服务器>/<目录>/feed.json
设备每 5 分钟拉一次清单，发现 $VERSION 比本机新就自己下载并蓝绿升级（只升不降）。

手动触发（不想等轮询 / 临时升一台）：
  curl -u admin:<口令> -X POST http://<设备>:<端口>/api/v1/system/feed/check     # 立即检查一次
  curl -u admin:<口令> -X POST http://<设备>:<端口>/api/v1/system/update \\
    -H 'Content-Type: application/json' \\
    -d '{"url":"<更新源>/kura-edge-$VERSION.jar","sha256":"$HOT_SHA","version":"$VERSION"}'
（202 受理后由外壳换 jar；蓝绿模式页面零中断，起不来会自动回滚，见 desktop/README.md）
HINT
    exit 0
fi

step "清理并准备 Tauri resources"
# 上一次打包把 JRE 拷进 target/ 时会带上只读位，残留会让下一次 O_TRUNC 报 EACCES
chmod -R u+w "$ROOT/src-tauri/target/$TARGET/release/resources" 2>/dev/null || true
rm -rf "$ROOT/src-tauri/target/$TARGET/release/resources"
# app/ 整个重建：残留的历史 jar（例如已下线的子模块）会被 bundle.resources 一起打进安装包，
# 白白撑大 exe 并让"安装目录里到底是哪份 jar"变得不可信。
rm -rf "$ROOT/src-tauri/resources/app"
mkdir -p "$ROOT/src-tauri/resources/app"
cp -f "$JAR" "$ROOT/src-tauri/resources/app/kura-edge.jar"

step "准备内嵌 Windows JRE"
bash "$ROOT/scripts/fetch-windows-jre.sh"

step "安装 Tauri CLI 依赖"
(cd "$ROOT" && [ -d node_modules ] || npm install)

step "Tauri 交叉编译 + NSIS 打包"
(cd "$ROOT" && npm run tauri -- build --runner cargo-xwin --target "$TARGET" --bundles nsis)

BUNDLE_DIR="$ROOT/src-tauri/target/$TARGET/release/bundle/nsis"
# 旧版本的 setup.exe 会滞留在 bundle/nsis/，必须按 mtime 取刚产出的那个，
# 否则 find 的自然顺序可能把上一版（甚至更早的版本）拷进 dist
INSTALLER="$(find "$BUNDLE_DIR" -maxdepth 1 -name '*setup.exe' \
    -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -1 | cut -f2- || true)"
[ -n "$INSTALLER" ] || { echo "未找到 NSIS 安装包（$BUNDLE_DIR）" >&2; exit 1; }

step "收集产物"
cp -f "$INSTALLER" "$OUT/"
printf '\n完成：%s (%s)\n' "$OUT/$(basename "$INSTALLER")" "$(du -h "$INSTALLER" | cut -f1)"
printf '同一次构建的热更新 jar：%s (%s)\n' "$HOT_JAR" "$(du -h "$HOT_JAR" | cut -f1)"
printf '更新清单（自动更新用）：%s\n' "$OUT/feed.json"
printf 'sha256：%s\n' "$HOT_SHA"
printf '（把 kura-edge-%s.jar 与 feed.json 一起上传到服务器同一目录即可自动更新；详见 desktop/README.md）\n' \
    "$VERSION"
