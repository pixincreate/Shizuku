#!/system/bin/sh

SOURCE_PATH="%%%STARTER_PATH%%%"
STARTER_PATH="/data/local/tmp/shizuku_starter"
PACKAGE_NAME="moe.shizuku.privileged.api"
PERMISSION_NAME="android.permission.WRITE_SECURE_SETTINGS"

echo "info: start.sh begin"

recreate_tmp() {
  echo "info: /data/local/tmp is possible broken, recreating..."
  rm -rf /data/local/tmp
  mkdir -p /data/local/tmp
}

broken_tmp() {
  echo "fatal: /data/local/tmp is broken, please try reboot the device or manually recreate it..."
  exit 1
}

if pm dump "$PACKAGE_NAME" | grep "$PERMISSION_NAME" | grep -q "granted=true"; then
    echo "Permission '$PERMISSION_NAME' is already granted to '$PACKAGE_NAME'."
else
    echo "Permission '$PERMISSION_NAME' not granted (or granted=false)."
    echo "Attempting to grant permission..."

    pm grant "$PACKAGE_NAME" "$PERMISSION_NAME"

    sleep 1
    echo "Verifying permission status after grant..."
    if pm dump package "$PACKAGE_NAME" | grep "$PERMISSION_NAME" | grep -q "granted=true"; then
        echo "Permission '$PERMISSION_NAME' successfully granted to '$PACKAGE_NAME'."
    else
        echo "Error: Failed to grant permission '$PERMISSION_NAME' to '$PACKAGE_NAME'."
        echo "Please check ADB output or device logs for errors."
    fi
fi

if [ -f "$SOURCE_PATH" ]; then
    echo "info: attempt to copy starter from $SOURCE_PATH to $STARTER_PATH"
    rm -f $STARTER_PATH

    cp "$SOURCE_PATH" $STARTER_PATH
    res=$?
    if [ $res -ne 0 ]; then
      recreate_tmp
      cp "$SOURCE_PATH" $STARTER_PATH

      res=$?
      if [ $res -ne 0 ]; then
        broken_tmp
      fi
    fi

    chmod 700 $STARTER_PATH
    chown 2000 $STARTER_PATH
    chgrp 2000 $STARTER_PATH
fi

if [ -f $STARTER_PATH ]; then
  echo "info: exec $STARTER_PATH"
    $STARTER_PATH "$1"
    result=$?
    if [ ${result} -ne 0 ]; then
        echo "info: shizuku_starter exit with non-zero value $result"
    else
        echo "info: shizuku_starter exit with 0"
    fi
    rm -f $STARTER_PATH 2>&1 > /dev/null
else
    echo "Starter file not exist, please open Shizuku and try again."
fi
