# Building Cloudstream Plugins

This guide provides complete instructions for building the BDIX Cloudstream plugins locally and setting up the required development environment.

---

## 1. Prerequisites

To build the plugins from source, ensure your environment has the following installed:

### 1.1. Java Development Kit (JDK) 17
- The project targets **Java 17 / JVM 11 compatibility**.
- **Installation (Ubuntu/Debian):**
  ```bash
  sudo apt update
  sudo apt install openjdk-17-jdk
  ```
- **Verify installation:**
  ```bash
  java -version
  # Should show OpenJDK 17.x
  ```

### 1.2. Android SDK
The plugins compile as Android library modules with:
- **Compile SDK:** `36`
- **Min SDK:** `21`

#### Installing Android SDK via Command Line Tools:
1. Download Command Line Tools from [developer.android.com/studio#command-line-tools-only](https://developer.android.com/studio#command-line-tools-only).
2. Extract to `$HOME/Android/Sdk/cmdline-tools/latest/`.
3. Set environment variables in your shell profile (`~/.bashrc` or `~/.zshrc`):
   ```bash
   export ANDROID_HOME="$HOME/Android/Sdk"
   export ANDROID_SDK_ROOT="$ANDROID_HOME"
   export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
   ```
4. Accept licenses and install platforms:
   ```bash
   yes | sdkmanager --licenses
   sdkmanager "platforms;android-36" "platform-tools" "build-tools;36.0.0"
   ```

---

## 2. Project Structure

```text
cloudstream/
├── .github/workflows/build.yml   # GitHub Actions CI/CD workflow
├── build.gradle.kts              # Root project configuration & plugin dependency injection
├── settings.gradle.kts           # Dynamic subproject inclusion
├── gradle.properties             # JVM arguments & AndroidX flags
├── repo.json                     # Repository manifest definition
├── DhakaFlix/                    # DhakaFlix plugin module
├── CityPlex/                     # CityPlex plugin module
├── CtgHall/                      # CtgHall plugin module
├── Infobase/                     # Infobase plugin module
├── RoyalFlix/                    # RoyalFlix plugin module
└── docs/                         # Documentation
```

`settings.gradle.kts` automatically scans the root directory and includes any folder that contains a `build.gradle.kts` file.

---

## 3. Build Commands

Make sure the Gradle wrapper executable has execution permissions:
```bash
chmod +x gradlew
```

### 3.1. Build All Plugins (`.cs3` files)
Builds all active plugin modules and generates `.cs3` files in each plugin's `build/` directory:
```bash
./gradlew make
```

### 3.2. Generate Repository Manifest (`plugins.json`)
Generates the repository index `build/plugins.json` containing metadata, hashes, and download links for all plugins:
```bash
./gradlew makePluginsJson
```

### 3.3. Full Build (Plugins + Repository Index)
Builds all plugins and generates the manifest in one command (used by CI):
```bash
./gradlew make makePluginsJson
```

### 3.4. Build a Specific Plugin
To build only a single plugin (e.g., `DhakaFlix`):
```bash
./gradlew :DhakaFlix:make
```
Or to verify compilation only:
```bash
./gradlew :DhakaFlix:compileDebugSources
```

### 3.5. Clean Build Artifacts
Removes all generated build files:
```bash
./gradlew clean
```

---

## 4. Build Outputs

After running `./gradlew make makePluginsJson`:
- **Plugin Binaries:** `<PluginName>/build/<PluginName>.cs3`
  - E.g., `DhakaFlix/build/DhakaFlix.cs3`
- **Repository Index:** `build/plugins.json`

---

## 5. Plugin Versioning

Whenever you make changes to a plugin, increment the integer `version` in its `build.gradle.kts`:

```kotlin
// DhakaFlix/build.gradle.kts
version = 14
```

> [!IMPORTANT]
> Cloudstream checks this integer value against the remote `plugins.json`. If the version number is not incremented, client apps will not prompt users to update the installed extension.

---

## 6. Testing Plugins Locally

### Option A: Local HTTP Server (Recommended)
1. Run a local HTTP server in the repository root:
   ```bash
   python3 -m http.server 8080
   ```
2. Create a temporary `local-repo.json` pointing to `http://<YOUR_LOCAL_IP>:8080/build/plugins.json`.
3. Add that repository URL in Cloudstream app settings under **Extensions** → **Add Repository**.

### Option B: Direct ADB Push
Push the `.cs3` file directly to the device storage:
```bash
adb push DhakaFlix/build/DhakaFlix.cs3 /sdcard/Download/
```
In Cloudstream, go to **Settings** → **Extensions** → **Install from file** and select the `.cs3` file.
