# shellcheck disable=SC2034
SKIPUNZIP=1

# Ask root managers that implement the hot-install protocol to activate this
# update immediately. Managers that do not recognize it ignore the request.
export MODULE_HOT_INSTALL_REQUEST=true

if [ "$BOOTMODE" ] && [ "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
elif [ "$BOOTMODE" ] && [ "$MAGISK_VER_CODE" ]; then
  ui_print "- Installing from Magisk app"
else
  ui_print "*********************************************************"
  ui_print "! Install from recovery is not supported"
  ui_print "! Please install from KernelSU or Magisk app"
  abort    "*********************************************************"
fi

VERSION=$(grep_prop version "$TMPDIR/module.prop")
ui_print "- Installing Weimo (Zygisk) $VERSION"

# Check architecture.
case "$ARCH" in
  arm64)
    ABI=arm64-v8a
    ;;
  arm)
    ABI=armeabi-v7a
    ;;
  *)
    abort "! Unsupported platform: $ARCH"
    ;;
esac
ui_print "- Device platform: $ARCH ($ABI)"

HAS32BIT=false
if [ "$ARCH" = "arm64" ]; then
  if [ -n "$(getprop ro.product.cpu.abilist32)" ] || [ -n "$(getprop ro.system.product.cpu.abilist32)" ]; then
    HAS32BIT=true
  fi
fi

ui_print "- Extracting module files"
extract "$ZIPFILE" 'module.prop' "$MODPATH"
extract "$ZIPFILE" 'config.sh'   "$MODPATH"
extract "$ZIPFILE" 'action.sh'   "$MODPATH"
extract "$ZIPFILE" 'webroot/index.html'     "$MODPATH"
extract "$ZIPFILE" 'webroot/css/app.css'    "$MODPATH"
extract "$ZIPFILE" 'webroot/js/bridge.js'   "$MODPATH"
extract "$ZIPFILE" 'webroot/js/app.js'      "$MODPATH"
extract "$ZIPFILE" 'webroot/js/kernelsu.js' "$MODPATH"

ui_print "- Extracting Zygisk native library"
mkdir -p "$MODPATH/zygisk"
extract "$ZIPFILE" "zygisk/$ABI.so" "$MODPATH/zygisk"
if [ "$HAS32BIT" = true ]; then
  extract "$ZIPFILE" "zygisk/armeabi-v7a.so" "$MODPATH/zygisk"
fi

ui_print "- Extracting Weimo payload"
mkdir -p "$MODPATH/payload"
extract "$ZIPFILE" 'payload/weimo.apk' "$MODPATH"
mkdir -p "$MODPATH/lib/$ABI"
extract "$ZIPFILE" "lib/$ABI/libpine.so"   "$MODPATH/lib/$ABI"
extract "$ZIPFILE" "lib/$ABI/libdexkit.so" "$MODPATH/lib/$ABI"
if [ "$HAS32BIT" = true ] && [ "$ABI" != "armeabi-v7a" ]; then
  mkdir -p "$MODPATH/lib/armeabi-v7a"
  extract "$ZIPFILE" "lib/armeabi-v7a/libpine.so"   "$MODPATH/lib/armeabi-v7a"
  extract "$ZIPFILE" "lib/armeabi-v7a/libdexkit.so" "$MODPATH/lib/armeabi-v7a"
fi

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/payload" 0 0 0755 0644
set_perm_recursive "$MODPATH/lib" 0 0 0755 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755

# KernelSU assigns the WebUI directory's mode and SELinux context itself.
# Do not include $MODPATH/webroot in a recursive set_perm call.
