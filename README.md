# P5XTESAL

Android modding framework and runtime container tailored specifically for **Persona 5: The Phantom X (P5X)**, enabling BepInEx plugins and real-time translation tools (such as `XUnity.AutoTranslator`) on mobile devices.

> **This is a specialized fork.** It is based on [All-Of-Us-Mods/FusionCore](https://github.com/All-Of-Us-Mods/FusionCore).
> All credit for the underlying Android IL2CPP runtime container goes to its original authors. This fork adapts the framework and introduces the specific compatibility fixes listed in [Changes in this fork](#changes-in-this-fork) so that *Persona 5: The Phantom X* and its third-party login SDKs can run smoothly. It is **not** affiliated with, endorsed by, or supported by the original project, Perfect World Games, Atlus, or SEGA.

## Credits

This project would not exist without the work of the following projects and people:

| Project | What it provides | Link |
| --- | --- | --- |
| **FusionCore** (All-Of-Us-Mods) | The Android runtime container this fork is based on: app, hooks, native loader, and game launching | https://github.com/All-Of-Us-Mods/FusionCore |
| **BepInEx** | Unity plugin loader / patcher framework used to run mods | https://github.com/BepInEx/BepInEx |
| **BepInExFusion** (All-Of-Us-Mods) | The custom BepInEx fork used by FusionCore | https://github.com/All-Of-Us-Mods/BepInExFusion |
| **.NET runtime** | Managed runtime shipped with the app (`libcoreclr.so` and friends) | https://github.com/dotnet/runtime |
| **Dobby** | Native hook framework (`libdobby.so`) | https://github.com/jmpews/Dobby |

Please check each upstream repository for its complete list of contributors and its own license.

## Compatibility

Current minimum supported Android version is 7.0 (API level 24).
No guarantees it will work on that version though,
as it has only been tested on Android 11 (API level 30) and above.

As of writing this, PC versions of BepInEx and MelonLoader still use .NET 6.0 and legacy MonoMod.
However, FusionCore uses .NET 10.0 and MonoMod reorganized, so if you directly use MonoMod in your mods,
you will need to make it compatible with the newer MonoMod version.

There is no Lemon/Melonloader support yet, just BepInEx Fusion (the original project's custom fork of BepInEx).

P5XTESAL automatically detects P5X's Unity version and downloads the corresponding unstripped `libunity.so` file upon setup.

You only need to follow the configuration steps below to make things work.

## Usage & Setup

1. Install **Persona 5: The Phantom X** on your Android device.
2. Download and install the **P5XTES** APK (or build it from source using the instructions below).
3. Launch **P5XTES**, select *Persona 5: The Phantom X* from the detected apps list, and let it initialize the BepInEx environment.
4. Place your compatible P5X BepInEx plugins (such as `XUnity.AutoTranslator` and the P5XTES translation files) into the generated BepInEx plugins directory and launch the game through the app.

## Changes in this fork

Fixes made while getting a game that uses a third-party login SDK to run inside FusionCore
(developed and tested with *Persona 5: The Phantom X* on an Android 16 device). Each one is small and
isolated, so they can be reviewed or sent upstream separately.

- **Intent extras and saved state use the game's ClassLoader** (`GameRuntime`). Fixes
  `BadParcelableException: ClassNotFoundException` when an SDK passes a `Serializable` between its own activities.
- **Game activities use the game's `Resources`** (`GameRuntime`). Fixes `Resource ID #0x0` in SDKs that
  override `getResources()` and keep their own copy.
- **`GameClassLoader` no longer has the host loader as its parent** (`GameRuntime`). Host classes
  (`dev.allofus.fusioncore.*`) are still reached through explicit delegation. Fixes
  `X cannot be cast to X` errors caused by `LayoutInflater` reusing the host's copy of an androidx view class.
- **`loading_view.xml` uses framework views only** (no `ConstraintLayout`), for the same reason.
- **The game's manifest ContentProviders are installed before `Application.onCreate()`** (`GameRuntime`).
  Fixes SDKs that initialise themselves from a provider, such as `Default FirebaseApp is not initialized`.
- **Google Play Services calling-package errors no longer kill the app** (`GameRuntime`). Calls made in the
  game's name are rejected by Google Play Services (it checks the real caller); only that specific
  `SecurityException` is dropped, everything else still crashes as usual.
- **`HOME` and `XDG_CACHE_HOME` point to the app cache directory** (`fusion.cpp`). MonoMod tried to create
  `/data/.cache`, which made every Harmony patch fail (and with it plugins such as XUnity.AutoTranslator).
- **Optional diagnostics** (`GameRuntime.DIAGNOSTICS`, off by default). When enabled it writes a
  `diag_*.txt` file to `/sdcard/FusionCore` before each game activity starts.

### Known limitations

- Features that make Google Play Services verify the app's package and signature (for example Google
  Sign-In, and possibly Play Games or in-app purchases) may not work, because inside FusionCore the real caller
  is `dev.allofus.fusioncore`, not the game.
- The changes above have only been tested on a single device and game. Other games may need other fixes.

## Build

Use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat :fusionApp:assembleDebug
```

Optional clean build:

```powershell
.\gradlew.bat clean :fusionApp:assembleDebug
```

The native code is built with CMake, so the Android SDK, NDK and CMake must be installed.

## License

FusionCore (and P5XTESAL) is licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
This fork keeps that license and all original copyright notices. If you distribute modified versions or APKs,
they must remain under the same license and make their source code available.

## Disclaimer

This is an unofficial fan-made tool for Persona 5: The Phantom X. Modifying or running an online game inside a 
custom runtime container may go against its terms of service and could result in account sanctions. Use it at your own risk.
