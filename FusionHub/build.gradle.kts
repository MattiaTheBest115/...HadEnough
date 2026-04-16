version = 1

cloudstream {
    description = "Catalogo unificato per anime, serie TV e film da AnimeUnity, AnimeWorld e StreamingCommunity"
    authors = listOf("Esentasse")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
        "TvSeries",
        "Movie",
        "Cartoon",
        "Documentary",
    )
    language = "it"
    requiresResources = true
    iconUrl = ""
}

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla:rhino:1.7.15")
}
