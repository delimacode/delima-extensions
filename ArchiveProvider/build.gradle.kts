version = 2

cloudstream {
    description = "Film dan serial TV domain publik dan Creative Commons dari Internet Archive (archive.org)"
    authors = listOf("delimacode")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
