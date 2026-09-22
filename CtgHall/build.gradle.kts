// use an integer for version numbers
version = 2

android {
    namespace = "com.tazihad"
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
}

cloudstream {
    description = "CTG Hall Movie/Show Provider with TMDB support for media description"
    authors = listOf("tazihad")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA",
        "Cartoon",
        "AsianDrama",
        "Others",
        "Documentary",
    )
    language = "bn"
}