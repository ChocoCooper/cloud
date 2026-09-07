// use an integer for version numbers
version = 7

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "#1 Best JAV Provider"
    language = "en"
    authors = listOf("ChocoCooper")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("NSFW")
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQYVzd07oSaIOJ4WYs2BAsQgmc8N-cj-hFpBaiYRSiq-Yah41Sl"
    isCrossPlatform = true
}
