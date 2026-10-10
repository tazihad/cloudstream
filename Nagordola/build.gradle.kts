// use an integer for version numbers
version = 3

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
    description = "Nagordola AList CDN Provider with TMDB integration"
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
        "Documentary"
    )
    language = "bn"
}

afterEvaluate {
    tasks.named<org.gradle.api.tasks.bundling.Zip>("make") {
        from("src/main/assets") {
            into("assets")
        }
    }
}

