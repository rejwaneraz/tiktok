package com.rejwane.tiktokgrab

/**
 * What a downloaded clip is about, guessed from the words around it: its caption, its author, the
 * sound it uses, and any label TikTok itself sent with the listing.
 *
 * The app never looks at the picture — there is no model on the phone and a frame cannot be
 * understood without one — so a clip whose caption says nothing lands in no tag. That limit is real
 * and shows in the numbers on the Explore chips: a tag appears only when at least two clips match.
 */
object Tags {

    private data class Group(val label: String, val words: List<String>)

    private val groups = listOf(
        Group("Saree", listOf("saree", "sari", "shari", "শাড়ি", "শাড়ি")),
        Group("Borka", listOf("borka", "burqa", "burka", "বোরকা", "abaya", "আবায়া")),
        Group("Hijab", listOf("hijab", "হিজাব", "niqab", "নিকাব", "headscarf")),
        Group("Panjabi", listOf("panjabi", "পাঞ্জাবি", "kurta", "kurti", "kurtis")),
        Group("School", listOf("school", "স্কুল", "college", "কলেজ", "uniform", "ইউনিফর্ম")),
        Group("Lehenga", listOf("lehenga", "লেহেঙ্গা", "ghagra", "লহেঙ্গা")),
        Group("Wedding", listOf("wedding", "বিয়ে", "biye", "holud", "হলুদ", "বিবাহ")),
        Group("Eid", listOf("eid", "ঈদ", "ঈদের")),
        Group("Party", listOf("party", "পার্টি", "birthday", "জন্মদিন")),
        Group("Makeup", listOf("makeup", "মেকআপ", "lipstick", "লিপস্টিক"))
    )

    /** Every tag this clip belongs to, in table order, de-duplicated. */
    fun of(video: LocalVideo, stat: Stat): List<String> {
        val hay = buildString {
            append(video.caption).append(' ')
            append(video.author).append(' ')
            append(video.name).append(' ')
            append(stat.sound).append(' ')
            append(stat.soundAuthor).append(' ')
            append(stat.labels.joinToString(" "))
        }.lowercase()

        // A three-letter word like "eid" would otherwise match inside longer ones; short words need
        // a boundary, longer ones are safe as a plain substring.
        fun hit(word: String): Boolean =
            if (word.length <= 3) {
                Regex("(^|[^a-z0-9])${Regex.escape(word)}([^a-z0-9]|$)").containsMatchIn(hay)
            } else hay.contains(word)

        return groups.filter { group -> group.words.any { hit(it) } }.map { it.label }
    }
}
