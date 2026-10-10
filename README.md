# Cloudstream Plugin Repo

A [Cloudstream](https://recloudstream.github.io/) plugin repository maintained by **tazihad** featuring BDIX and CDN-based media extensions for Bangladesh with TMDb metadata enrichment.

## Installing the Repository

### Method 1 — One-Tap Install

[Install Tazihad's Repo →](https://raw.githubusercontent.com/tazihad/cloudstream/master/repo.json)

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

Once the repository is added, refresh it and install your desired extensions.

---

## Supported Extensions & Media Servers

| Extension | Description | Server(s) / Base URL | Network |
|-----------|-------------|----------------------|---------|
| **DhakaFlix** | Local BDIX media servers with TMDb metadata | `172.16.50.7`, `172.16.50.9`, `172.16.50.10`, `172.16.50.12`, `172.16.50.14` | BDIX Only |
| **CityPlex** | Multi-server BDIX movie & TV series archive | `10.247.249.2`, `10.247.249.3`, `10.247.249.4`, `10.247.249.6`, `10.247.249.7`, `10.247.249.8`, `10.247.249.22` | BDIX Only |
| **CTG Hall** | Fast-stream Chittagong BDIX media portal | `https://fs.plus.net.bd` | BDIX Only |
| **Infobase** | Multi-HDD media library (Movies, Series, Anime) | `http://103.225.94.27` | BDIX Only |
| **Nagordola** | AList CDN provider with bundled pre-crawled database | `https://cdn.nagordola.com.bd` | CDN / BDIX / Broadband |
| **RoyalFlix** | Multi-disk BDIX entertainment server | `http://royalflix.net` | BDIX Only |

> **Network Note:** Servers marked as **BDIX Only** are accessible only when connected to an ISP peered with the **BDIX (Bangladesh Internet Exchange)** network. Nagordola connects via high-speed CDN and bundled metadata.

---

### Extension Details

#### 1. DhakaFlix
Provides structured access across five dedicated DhakaFlix server clusters:
- **DhakaFlix 7** (`http://172.16.50.7/DHAKA-FLIX-7/`): General movies & releases
- **DhakaFlix 9** (`http://172.16.50.9/DHAKA-FLIX-9/`): Awards, WWE, Korean movies, Documentaries
- **DhakaFlix 10** (`http://172.16.50.10/DHAKA-FLIX-10/`): Anime & Cartoon TV Series
- **DhakaFlix 12** (`http://172.16.50.12/DHAKA-FLIX-12/`): English and international TV / Web Series
- **DhakaFlix 14** (`http://172.16.50.14/DHAKA-FLIX-14/`): Korean TV & Web Series, Animation (1080p)

#### 2. CityPlex
Aggregates content across 7 internal CityPlex nodes:
- `http://10.247.249.2`: English Movies & Foreign Language Movies
- `http://10.247.249.3`: Hindi Movies & South Indian (Hindi Dubbed)
- `http://10.247.249.4`: Animation Movies & Bangla Movies
- `http://10.247.249.6`: English Movies (1080p & 720p)
- `http://10.247.249.7`: Hindi & South Indian Movies
- `http://10.247.249.8`: Bangladeshi Movies & Foreign Movies
- `http://10.247.249.22`: TV & Web Series, Islamic Series

#### 3. CTG Hall
Fast-stream media server based in Chittagong:
- `https://fs.plus.net.bd`: English Movies, Bollywood, South Indian, Indian Bangla, Asian & Anime Movies, TV Shows, and Indian Web Series.

#### 4. Infobase
High-capacity multi-HDD BDIX repository:
- `http://103.225.94.27`: Hollywood (HDD-1, HDD-2, HDD-3, HDD-5), Bollywood, Bangla, South Indian, Foreign Movies, Series & Anime.

#### 5. Nagordola
AList CDN streaming extension with an offline pre-crawled database architecture:
- `https://cdn.nagordola.com.bd`: Direct CDN streaming (`/d/`) across 19 categories covering English, Hindi, Bangla, Tamil, Telugu, Malayalam, Korean, Asian, Foreign, Anime movies, and TV shows.

#### 6. RoyalFlix
Multi-disk media repository:
- `http://royalflix.net`: Hollywood (New & Classic), Bollywood, South Indian, Bangla, TV Shows, and Documentaries across `disk1` through `disk6`.

---

## Setting Up a TMDb API Key

Extensions in this repository use **TMDb (The Movie Database)** to fetch high-resolution posters, plot summaries, release years, and IMDb/TMDb ratings. A single TMDb API key is shared automatically across all extensions in this repository.

1. Go to [The Movie Database (TMDb)](https://www.themoviedb.org/) and create a free account.
2. Go to **Settings** → **API** from your profile menu.
3. Generate or copy your free **API Key (v3 auth)**.
4. In Cloudstream, open any extension's settings (e.g., **DhakaFlix** or **Nagordola**) and paste the API key.
5. Tap **Save** — metadata and posters will automatically load across all installed extensions.

---

## Building Locally

To build all plugins locally:

```bash
./gradlew make makePluginsJson
```

Artifacts will be generated in each module's `build/` folder (`<ModuleName>.cs3`) and published to `plugins.json`.

---

## License

This project is provided for personal educational use on the Cloudstream platform.