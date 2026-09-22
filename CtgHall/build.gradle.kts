// use an integer for version numbers
version = 1

android {
    namespace = "com.tazihad"
}

cloudstream {
    description = "CTG Hall Movie/Show Provider"
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