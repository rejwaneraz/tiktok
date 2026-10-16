package com.rejwane.tiktokgrab

/**
 * Comment pictures are deliberately not downloaded — a boy, a girl and a neutral face are drawn
 * once inside the app, and the name decides which one fits. A guess, so it is only ever a
 * placeholder; a comment that carries its own photo is the one case where we fetch the real image.
 */
object Names {

    const val UNKNOWN = 0
    const val BOY = 1
    const val GIRL = 2

    private val girl = listOf(
        "akter", "begum", "khatun", "nahrin", "nasrin", "sultana", "parvin", "rumana", "shirin",
        "nusrat", "jannat", "fariha", "sadia", "sumaiya", "sumaya", "mariam", "fatima", "ayesha",
        "aisha", "khadija", "ruqayya", "zainab", "rubaiya", "tahmina", "tania", "tamanna", "trisha",
        "priya", "rupa", "shapla", "mim", "iti", "liza", "rin", "mili", "keya", "meghla", "nishi",
        "boss", "queen", "princess", "miss", "mrs", "auntie", "girl", "she ", "her ", "mom ", "maa",
        "ammu", "chi", "kun", "chan", "ladies", "female", "madam", "bi", "bee"
    )

    private val boy = listOf(
        "md", "muhammad", "mohammad", "mahmud", "mohammed", "rasel", "rakib", "sabbir", "tanvir",
        "hasan", "hussein", "hossain", "ahmed", "khan", "islam", "alam", "chowdhury", "sarkar",
        "talukder", "howlader", "molla", "sheikh", "mondal", "barua", "chakma", "mahedi", "mehedi",
        "shakib", "tamim", "mushfiqur", "sakib", "rajib", "naim", "sabbir", "jubayer", "rifat",
        "shohag", "sohel", "jabbar", "karim", "rahim", "kalam", "jalal", "jamal", "bulbul", "amir",
        "king", "boy", "bro", "bhai", "mr ", "sir", "he ", "him", "male", "uncle", "abba", "baba"
    )

    fun guess(name: String): Int {
        val text = " " + name.lowercase() + " "
        var score = 0
        girl.forEach { if (text.contains(it)) score-- }
        boy.forEach { if (text.contains(it)) score++ }
        return when {
            score > 0 -> BOY
            score < 0 -> GIRL
            else -> UNKNOWN
        }
    }

    fun drawable(kind: Int) = when (kind) {
        BOY -> R.drawable.av_boy
        GIRL -> R.drawable.av_girl
        else -> R.drawable.av_person
    }
}
