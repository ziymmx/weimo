#!/system/bin/sh
# Weimo (微末) Zygisk — 模块动作：重启微信
# 用于在 KernelSU / Magisk 模块动作菜单中一键重启微信使开关生效。

MODDIR=${0%/*}
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:$PATH

echo "- 正在结束微信进程..."
if ! am force-stop com.tencent.mm; then
  echo "  Force-stop 微信失败" >&2
  exit 1
fi

echo "- 正在重新启动微信..."
if ! am start -n com.tencent.mm/com.tencent.mm.ui.LauncherUI; then
  echo "  启动微信失败" >&2
  exit 1
fi

echo "微信已重启，开关将在新进程中生效。"
