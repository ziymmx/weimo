#!/system/bin/sh
#
# Weimo (微末) Zygisk — 功能开关配置脚本
#
# 由 KernelSU WebUI / 模块动作调用。状态持久化在模块目录之外，
# 模块升级不会丢失用户开关选择。
#
# 接口：
#   list               列出全部功能开关（name<TAB>0|1）
#   set <name> <0|1>   设置单个功能
#   reset              恢复默认（全部启用）
#   mask               输出当前启用的功能位掩码（十进制，调试用）
#
# 说明：缺失的行按「启用(1)」处理，与 native companion 默认策略一致。

STATE_DIR=/data/adb/weimo_zygisk
CONFIG_FILE=$STATE_DIR/config.tsv
LOCK_DIR=$STATE_DIR/.config.lock
LOCK_RETRIES=10

export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:$PATH
umask 077

# 与 com.ziymmx.wx.util.FeatureFlags 保持一致（顺序即位的顺序）
FEATURES="anti-recall force-tablet anti-xposed-detect disable-hot-update moments-anti-recall moments-comment-anti-recall moments-ad-block"

log_msg() {
  mkdir -p "$STATE_DIR" 2>/dev/null || return 0
  ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || printf unknown)
  printf '%s [%s] %s\n' "$ts" "$1" "$2" >> "$STATE_DIR/webui.log" 2>/dev/null || true
  chmod 600 "$STATE_DIR/webui.log" 2>/dev/null || true
}

ensure_state_dir() {
  mkdir -p "$STATE_DIR" || { echo "cannot create $STATE_DIR" >&2; return 1; }
  chmod 700 "$STATE_DIR" || { echo "cannot chmod $STATE_DIR" >&2; return 1; }
}

release_lock() {
  if [ -f "$LOCK_DIR/pid" ]; then
    owner=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    if [ "$owner" = "$$" ]; then
      rm -f "$LOCK_DIR/pid"
    fi
  fi
  rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
  ensure_state_dir || return 1
  attempt=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ -f "$LOCK_DIR/pid" ]; then
      owner=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
      case "$owner" in
        ''|*[!0-9]*)
          if [ "$attempt" -ge 2 ]; then
            rm -f "$LOCK_DIR/pid"; rmdir "$LOCK_DIR" 2>/dev/null && continue
          fi
          ;;
        *)
          if ! kill -0 "$owner" 2>/dev/null; then
            rm -f "$LOCK_DIR/pid"; rmdir "$LOCK_DIR" 2>/dev/null && continue
          fi
          ;;
      esac
    elif [ "$attempt" -ge 2 ]; then
      rmdir "$LOCK_DIR" 2>/dev/null && continue
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$LOCK_RETRIES" ]; then
      echo "timed out waiting for config lock" >&2
      return 1
    fi
    sleep 1
  done
  printf '%s\n' "$$" > "$LOCK_DIR/pid" || { rmdir "$LOCK_DIR" 2>/dev/null || true; return 1; }
  return 0
}

is_valid_value() { [ "$1" = 0 ] || [ "$1" = 1 ]; }

# 读取某个功能的当前值；配置缺失或文件不存在一律视为 1（启用）。
read_value() {
  name=$1
  result=1
  if [ -f "$CONFIG_FILE" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        ''|\#*) continue ;;
      esac
      set -- $line
      if [ "$1" = "$name" ] && [ -n "$2" ]; then
        result=$2
        break
      fi
    done < "$CONFIG_FILE"
  fi
  printf '%s' "$result"
}

list_features() {
  for feature in $FEATURES; do
    printf '%s\t%s\n' "$feature" "$(read_value "$feature")"
  done
}

publish_config() {
  tmp=$1
  chmod 600 "$tmp" || { rm -f "$tmp"; return 1; }
  mv -f "$tmp" "$CONFIG_FILE" || { rm -f "$tmp"; return 1; }
}

set_feature() {
  name=$1
  value=$2
  is_valid_value "$value" || { echo "value must be 0 or 1" >&2; return 2; }
  found=0
  for feature in $FEATURES; do
    [ "$feature" = "$name" ] && found=1
  done
  [ "$found" = 1 ] || { echo "unknown feature: $name" >&2; return 2; }

  acquire_lock || return 1
  tmp=$CONFIG_FILE.tmp.$$
  rm -f "$tmp"
  : > "$tmp" || { release_lock; echo "cannot create temp file" >&2; return 1; }
  for feature in $FEATURES; do
    if [ "$feature" = "$name" ]; then
      val=$value
    else
      val=$(read_value "$feature")
    fi
    printf '%s\t%s\n' "$feature" "$val" >> "$tmp" || { rm -f "$tmp"; release_lock; return 1; }
  done
  publish_config "$tmp" || { release_lock; echo "cannot publish config" >&2; return 1; }
  release_lock
  log_msg INFO "set $name=$value"
}

reset_features() {
  acquire_lock || return 1
  tmp=$CONFIG_FILE.tmp.$$
  rm -f "$tmp"
  : > "$tmp" || { release_lock; echo "cannot create temp file" >&2; return 1; }
  for feature in $FEATURES; do
    printf '%s\t1\n' "$feature" >> "$tmp" || { rm -f "$tmp"; release_lock; return 1; }
  done
  publish_config "$tmp" || { release_lock; echo "cannot publish config" >&2; return 1; }
  release_lock
  log_msg INFO "reset all features"
}

feature_mask() {
  mask=0
  bit=1
  for feature in $FEATURES; do
    v=$(read_value "$feature")
    if [ "$v" = 1 ]; then
      mask=$((mask | bit))
    fi
    bit=$((bit << 1))
  done
  printf '%s\n' "$mask"
}

case "$1" in
  list)
    list_features
    ;;
  set)
    if [ $# -ne 3 ]; then
      echo "usage: $0 set <feature> <0|1>" >&2
      exit 64
    fi
    set_feature "$2" "$3"
    ;;
  reset)
    reset_features
    ;;
  mask)
    feature_mask
    ;;
  *)
    echo "usage: $0 {list|set <feature> <0|1>|reset|mask}" >&2
    exit 64
    ;;
esac
