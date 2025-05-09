# Shizuku

## Disclaimer

THIS IS A **FORK** OF SHIZUKU. IF YOU'RE LOOKING FOR SHIZUKU FROM RIKKA, THIS IS NOT THE PLACE.
VISIT THE OFFICIAL REPO [**_HERE_**](https://github.com/RikkaApps/Shizuku)

## Additional Features that this fork provides

- **Auto-start**: This fork provides an auto-start feature on root-less devices that allows the Shizuku service to start automatically on boot. This is useful for users who want to ensure that the service is always running without manual intervention.
- **Rooted-Debugging**: This fork allows you to debug the Shizuku service while it is running with root privileges. This feature is only available on `userdebug` and `eng` builds of Android.
- **Stealth**: Prevent Shizuku from being detected by other apps. This is done by randomizing the Shizuku tmp folder name and also by deleting the starter file after the service is started.

### Usage of auto-start

- Follow the instructions for setting up Shizuku through Wireless ADB by pairing the app
  - From the `Settings`, enable `Start on boot (wireless ADB)`
    - `WRITE_SECURE_SETTINGS` permission needs to be granted prior to enabling this setting and this can be enabled either by `rish` or by connecting the device to the machine
    - Run the following command:
      ```bash
      adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
      ```

> [!WARNING]
> `WRITE_SECURE_SETTINGS` is a very sensitive permission and enable it only if you know what you're doing.
> The developer of this fork is not responsible for whatever may happen later on.

> [!NOTE]
> Auto restart service is untested

## Background

When developing apps that requires root, the most common method is to run some commands in the su shell. For example, there is an app that uses the `pm enable/disable` command to enable/disable components.

This method has very big disadvantages:

1. **Extremely slow** (Multiple process creation)
2. Needs to process texts (**Super unreliable**)
3. The possibility is limited to available commands
4. Even if ADB has sufficient permissions, the app requires root privileges to run

Shizuku uses a completely different way. See detailed description below.

## User guide & Download

<https://shizuku.rikka.app/>

## How does Shizuku work?

First, we need to talk about how app use system APIs. For example, if the app wants to get installed apps, we all know we should use `PackageManager#getInstalledPackages()`. This is actually an interprocess communication (IPC) process of the app process and system server process, just the Android framework did the inner works for us.

Android uses `binder` to do this type of IPC. `Binder` allows the server-side to learn the uid and pid of the client-side, so that the system server can check if the app has the permission to do the operation.

Usually, if there is a "manager" (e.g., `PackageManager`) for apps to use, there should be a "service" (e.g., `PackageManagerService`) in the system server process. We can simply think if the app holds the `binder` of the "service", it can communicate with the "service". The app process will receive binders of system services on start.

Shizuku guides users to run a process, Shizuku server, with root or ADB first. When the app starts, the `binder` to Shizuku server will also be sent to the app.

The most important feature Shizuku provides is something like be a middle man to receive requests from the app, sent them to the system server, and send back the results. You can see the `transactRemote` method in `rikka.shizuku.server.ShizukuService` class, and `moe.shizuku.api.ShizukuBinderWrapper` class for the detail.

So, we reached our goal, to use system APIs with higher permission. And to the app, it is almost identical to the use of system APIs directly.

## Developer guide

### API & sample

https://github.com/RikkaApps/Shizuku-API

### Migrating from pre-v11

> Existing applications still works, of course.

https://github.com/RikkaApps/Shizuku-API#migration-guide-for-existing-applications-use-shizuku-pre-v11

### Attention

1. ADB permissions are limited

   ADB has limited permissions and different on various system versions. You can see permissions granted to ADB [here](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/packages/Shell/AndroidManifest.xml).

   Before calling the API, you can use `ShizukuService#getUid` to check if Shizuku is running user ADB, or use `ShizukuService#checkPermission` to check if the server has sufficient permissions.

2. Hidden API limitation from Android 9

   As of Android 9, the usage of the hidden APIs is limited for normal apps. Please use other methods (such as <https://github.com/LSPosed/AndroidHiddenApiBypass>).

3. Android 8.0 & ADB

   At present, the way Shizuku service gets the app process is to combine `IActivityManager#registerProcessObserver` and `IActivityManager#registerUidObserver` (26+) to ensure that the app process will be sent when the app starts. However, on API 26, ADB lacks permissions to use `registerUidObserver`, so if you need to use Shizuku in a process that might not be started by an Activity, it is recommended to trigger the send binder by starting a transparent activity.

4. Direct use of `transactRemote` requires attention

   * The API may be different under different Android versions, please be sure to check it carefully. Also, the `android.app.IActivityManager` has the aidl form in API 26 and later, and `android.app.IActivityManager$Stub` exists only on API 26.

   * `SystemServiceHelper.getTransactionCode` may not get the correct transaction code, such as `android.content.pm.IPackageManager$Stub.TRANSACTION_getInstalledPackages` does not exist on API 25 and there is `android.content.pm.IPackageManager$Stub.TRANSACTION_getInstalledPackages_47` (this situation has been dealt with, but it is not excluded that there may be other circumstances). This problem is not encountered with the `ShizukuBinderWrapper` method.

## Developing Shizuku itself

### Build

- Clone with `git clone --recurse-submodules`
- Run gradle task `:manager:assembleDebug` or `:manager:assembleRelease`

The `:manager:assembleDebug` task generates a debuggable server. You can attach a debugger to `shizuku_server` to debug the server. Be aware that, in Android Studio, "Run/Debug configurations" - "Always install with package manager" should be checked, so that the server will use the latest code.

## License

The code for this project is available under the Apache-2.0 license.

### Exceptions

* You are **FORBIDDEN** to use image files listed below in any way (unless for displaying Shizuku itself).

  ```
  manager/src/main/res/mipmap-hdpi/ic_launcher.png
  manager/src/main/res/mipmap-hdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-hdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
  ```

* For the project as a whole, it is not free.
You are **FORBIDDEN** to distribute the apk compiled by **you**
(including modified, e.g., rename app name "Shizuku" to something else)
to any store (IBNLT Google Play Store, F-Droid, Amazon Appstore etc.).
