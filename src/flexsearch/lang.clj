(ns flexsearch.lang)

(def en
  {:filter
   ["a" "about" "above"
    "after" "again" "against" "all" "also" "am" "an"
    "and" "any" "are" "aren't" "as"
    "at" "be" "because" "been" "before"
    "being" "below" "both" "but" "by"
    "can" "cannot" "can't" "come" "could" "couldn't" "did"
    "didn't" "do" "does" "doesn't" "doing" "dont" "down" "during"
    "each" "even" "few" "first" "for" "from" "further"
    "get" "go" "had" "hadn't" "has" "hasn't" "have" "haven't" "having"
    "he" "hed" "her" "here" "here's" "hers" "herself" "hes" "him" "himself" "his"
    "how" "how's" "i" "id" "if" "ill" "im" "in" "into"
    "is" "isn't" "it" "it's" "itself" "i've" "just" "know" "let's" "like"
    "make" "me" "more" "most" "mustn't" "my" "myself" "new" "no" "nor"
    "not" "now" "of" "off" "on" "once" "only" "or" "other" "ought"
    "our" "our's" "ourselves" "out" "over" "own" "same" "say" "see"
    "shan't" "she" "she'd" "shell" "shes" "should" "shouldn't" "so" "some" "such"
    "than" "that" "that's" "the" "their" "theirs" "them" "themselves" "then" "there"
    "there's" "these" "they" "they'd" "they'll" "they're" "they've" "this" "those"
    "through" "time" "to" "too" "until" "up" "us" "very" "want"
    "was" "wasn't" "way" "we" "wed" "well" "were" "weren't" "we've" "what"
    "what's" "when" "when's" "where" "where's" "which" "while" "who" "whom" "who's" "why"
    "why's" "will" "with" "won't" "would" "wouldn't" "you" "you'd" "you'll"
    "your" "you're" "your's" "yourself" "yourselves" "you've"],
   :stemmer
   {:tional "tion",:alli "al",
    :alize "al",:ational "ate",:ent "",:iviti "ive",:izer "ize",:er "",
    :ation "ate",:ment "",:ence "",:ical "ic",
    :ic "",:biliti "ble",:ization "ize",:entli "ent",:anci "ance",
    :eli "e",:ism "",:enci "ence",:ible "",:bli "ble",:ement "",
    :ator "ate",:icate "ic",:ousli "ous",:ate "",:ful "",:iciti "ic",
    :al "",:ness "",:ize "",:ou "",:ousness "ous",:iveness "ive",
    :ive "",:ous "",:ant "",:alism "al",:ance "",:able "",
    :iti "",:ative "",:logi "log",:aliti "al",:fulness "ful"}})
