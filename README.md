# Cloudstream Plugin Repo

A [Cloudstream](https://recloudstream.github.io/) plugin repository maintained by **tazihad** featuring the **DhakaFlix** BDIX extension.

## Installing the Repository

### Method 1 — One-Tap Install

[Install Tazihad's BDIX Repo →](https://raw.githubusercontent.com/tazihad/cloudstream/master/repo.json)

Tap the link above to add the repository directly to Cloudstream.

### Method 2 — Manual Install

1. Open the **Cloudstream** app.
2. Go to **Settings** → **Extensions**.
3. Tap **Add Repository**.
4. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/tazihad/cloudstream/master/repo.json
   ```
5. Tap **Save** / **Add**.

Once the repository is added, refresh it and install the **DhakaFlix** extension.

## DhakaFlix Extension

The DhakaFlix extension provides access to local BDIX media servers with TMDb metadata enrichment:

| Provider      | Server              |
|---------------|---------------------|
| (BDIX) DhakaFlix 7  | `172.16.50.7`  |
| (BDIX) DhakaFlix 9  | `172.16.50.9`  |
| (BDIX) DhakaFlix 12 | `172.16.50.12` |
| (BDIX) DhakaFlix 14 | `172.16.50.14` |

> These servers are reachable only on the **BDIX (Bangladesh Internet Exchange)** network.

### Setting Up a TMDb API Key

DhakaFlix uses **TMDb** to fetch movie/TV metadata and posters. An API key is required:

1. Go to the [TMDb website](https://www.themoviedb.org/) and create a free account.
2. Once logged in, go to **Settings** → **API** in your profile menu.
3. Fill out the request form and submit it (approval is usually quick).
4. Copy your **API Key** from the **API Key** section.
5. In Cloudstream, open the **DhakaFlix** extension settings and paste the key.
6. Done — metadata will now be loaded for eligible titles.

> **Note:** Keep your API key private. Do not share it or commit it to any repository.

## Building

To build the plugin locally:

```bash
./gradlew assemble
```

The built plugin APK will be placed in the module's `build` directory.

## License

This project is provided as-is for personal use on the Cloudstream platform.