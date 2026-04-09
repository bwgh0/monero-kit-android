package io.horizontalsystems.monerokit

import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.Mnemonic
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * BIP39 to Monero Legacy Converter following Cake Wallet's implementation
 *
 * This matches the exact approach used by Cake Wallet in Dart:
 * 1. Generate BIP39 seed
 * 2. Derive BIP32 key at m/44'/128'/accountIndex'/0/0
 * 3. Reduce private key with Ed25519 curve order (no Keccak hashing)
 * 4. Encode as Monero legacy mnemonic
 */
object CakeWalletStyleConverter {

    // Ed25519 curve order (same as Monero curve order)
    private val ED25519_CURVE_ORDER = BigInteger("1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED", 16)

    // Monero prefix length for checksum
    private val MONERO_PREFIX_LENGTH = 3

    // Complete Monero English wordlist (1626 words)
    val MONERO_WORDLIST = arrayOf(
        "abbey", "abducts", "ability", "ablaze", "abnormal", "abort", "abrasive", "absorb",
        "abyss", "academy", "aces", "aching", "acidic", "acoustic", "acquire", "across",
        "actress", "acumen", "adapt", "addicted", "adept", "adhesive", "adjust", "adopt",
        "adrenalin", "adult", "adventure", "aerial", "afar", "affair", "afield", "afloat",
        "afoot", "afraid", "after", "against", "agenda", "aggravate", "agile", "aglow",
        "agnostic", "agony", "agreed", "ahead", "aided", "ailments", "aimless", "airport",
        "aisle", "ajar", "akin", "alarms", "album", "alchemy", "alerts", "algebra",
        "alkaline", "alley", "almost", "aloof", "alpine", "already", "also", "altitude",
        "alumni", "always", "amaze", "ambush", "amended", "amidst", "ammo", "amnesty",
        "among", "amply", "amused", "anchor", "android", "anecdote", "angled", "ankle",
        "annoyed", "answers", "antics", "anvil", "anxiety", "anybody", "apart", "apex",
        "aphid", "aplomb", "apology", "apply", "apricot", "aptitude", "aquarium", "arbitrary",
        "archer", "ardent", "arena", "argue", "arises", "army", "around", "arrow",
        "arsenic", "artistic", "ascend", "ashtray", "aside", "asked", "asleep", "aspire",
        "assorted", "asylum", "athlete", "atlas", "atom", "atrium", "attire", "auburn",
        "auctions", "audio", "august", "aunt", "austere", "autumn", "avatar", "avidly",
        "avoid", "awakened", "awesome", "awful", "awkward", "awning", "awoken", "axes",
        "axis", "axle", "aztec", "azure", "baby", "bacon", "badge", "baffles",
        "bagpipe", "bailed", "bakery", "balding", "bamboo", "banjo", "baptism", "basin",
        "batch", "bawled", "bays", "because", "beer", "befit", "begun", "behind",
        "being", "below", "bemused", "benches", "berries", "bested", "betting", "bevel",
        "beware", "beyond", "bias", "bicycle", "bids", "bifocals", "biggest", "bikini",
        "bimonthly", "binocular", "biology", "biplane", "birth", "biscuit", "bite", "biweekly",
        "blender", "blip", "bluntly", "boat", "bobsled", "bodies", "bogeys", "boil",
        "boldly", "bomb", "border", "boss", "both", "bounced", "bovine", "bowling",
        "boxes", "boyfriend", "broken", "brunt", "bubble", "buckets", "budget", "buffet",
        "bugs", "building", "bulb", "bumper", "bunch", "business", "butter", "buying",
        "buzzer", "bygones", "byline", "bypass", "cabin", "cactus", "cadets", "cafe",
        "cage", "cajun", "cake", "calamity", "camp", "candy", "casket", "catch",
        "cause", "cavernous", "cease", "cedar", "ceiling", "cell", "cement", "cent",
        "certain", "chlorine", "chrome", "cider", "cigar", "cinema", "circle", "cistern",
        "citadel", "civilian", "claim", "click", "clue", "coal", "cobra", "cocoa",
        "code", "coexist", "coffee", "cogs", "cohesive", "coils", "colony", "comb",
        "cool", "copy", "corrode", "costume", "cottage", "cousin", "cowl", "criminal",
        "cube", "cucumber", "cuddled", "cuffs", "cuisine", "cunning", "cupcake", "custom",
        "cycling", "cylinder", "cynical", "dabbing", "dads", "daft", "dagger", "daily",
        "damp", "dangerous", "dapper", "darted", "dash", "dating", "dauntless", "dawn",
        "daytime", "dazed", "debut", "decay", "dedicated", "deepest", "deftly", "degrees",
        "dehydrate", "deity", "dejected", "delayed", "demonstrate", "dented", "deodorant", "depth",
        "desk", "devoid", "dewdrop", "dexterity", "dialect", "dice", "diet", "different",
        "digit", "dilute", "dime", "dinner", "diode", "diplomat", "directed", "distance",
        "ditch", "divers", "dizzy", "doctor", "dodge", "does", "dogs", "doing",
        "dolphin", "domestic", "donuts", "doorway", "dormant", "dosage", "dotted", "double",
        "dove", "down", "dozen", "dreams", "drinks", "drowning", "drunk", "drying",
        "dual", "dubbed", "duckling", "dude", "duets", "duke", "dullness", "dummy",
        "dunes", "duplex", "duration", "dusted", "duties", "dwarf", "dwelt", "dwindling",
        "dying", "dynamite", "dyslexic", "each", "eagle", "earth", "easy", "eating",
        "eavesdrop", "eccentric", "echo", "eclipse", "economics", "ecstatic", "eden", "edgy",
        "edited", "educated", "eels", "efficient", "eggs", "egotistic", "eight", "either",
        "eject", "elapse", "elbow", "eldest", "eleven", "elite", "elope", "else",
        "eluded", "emails", "ember", "emerge", "emit", "emotion", "empty", "emulate",
        "energy", "enforce", "enhanced", "enigma", "enjoy", "enlist", "enmity", "enough",
        "enraged", "ensign", "entrance", "envy", "epoxy", "equip", "erase", "erected",
        "erosion", "error", "eskimos", "espionage", "essential", "estate", "etched", "eternal",
        "ethics", "etiquette", "evaluate", "evenings", "evicted", "evolved", "examine", "excess",
        "exhale", "exit", "exotic", "exquisite", "extra", "exult", "fabrics", "factual",
        "fading", "fainted", "faked", "fall", "family", "fancy", "farming", "fatal",
        "faulty", "fawns", "faxed", "fazed", "feast", "february", "federal", "feel",
        "feline", "females", "fences", "ferry", "festival", "fetches", "fever", "fewest",
        "fiat", "fibula", "fictional", "fidget", "fierce", "fifteen", "fight", "films",
        "firm", "fishing", "fitting", "five", "fixate", "fizzle", "fleet", "flippant",
        "flying", "foamy", "focus", "foes", "foggy", "foiled", "folding", "fonts",
        "foolish", "fossil", "fountain", "fowls", "foxes", "foyer", "framed", "friendly",
        "frown", "fruit", "frying", "fudge", "fuel", "fugitive", "fully", "fuming",
        "fungal", "furnished", "fuselage", "future", "fuzzy", "gables", "gadget", "gags",
        "gained", "galaxy", "gambit", "gang", "gasp", "gather", "gauze", "gave",
        "gawk", "gaze", "gearbox", "gecko", "geek", "gels", "gemstone", "general",
        "geometry", "germs", "gesture", "getting", "geyser", "ghetto", "ghost", "giant",
        "giddy", "gifts", "gigantic", "gills", "gimmick", "ginger", "girth", "giving",
        "glass", "gleeful", "glide", "gnaw", "gnome", "goat", "goblet", "godfather",
        "goes", "goggles", "going", "goldfish", "gone", "goodbye", "gopher", "gorilla",
        "gossip", "gotten", "gourmet", "governing", "gown", "greater", "grunt", "guarded",
        "guest", "guide", "gulp", "gumball", "guru", "gusts", "gutter", "guys",
        "gymnast", "gypsy", "gyrate", "habitat", "hacksaw", "haggled", "hairy", "hamburger",
        "happens", "hashing", "hatchet", "haunted", "having", "hawk", "haystack", "hazard",
        "hectare", "hedgehog", "heels", "hefty", "height", "hemlock", "hence", "heron",
        "hesitate", "hexagon", "hickory", "hiding", "highway", "hijack", "hiker", "hills",
        "himself", "hinder", "hippo", "hire", "history", "hitched", "hive", "hoax",
        "hobby", "hockey", "hoisting", "hold", "honked", "hookup", "hope", "hornet",
        "hospital", "hotel", "hounded", "hover", "howls", "hubcaps", "huddle", "huge",
        "hull", "humid", "hunter", "hurried", "husband", "huts", "hybrid", "hydrogen",
        "hyper", "iceberg", "icing", "icon", "identity", "idiom", "idled", "idols",
        "igloo", "ignore", "iguana", "illness", "imagine", "imbalance", "imitate", "impel",
        "inactive", "inbound", "incur", "industrial", "inexact", "inflamed", "ingested", "initiate",
        "injury", "inkling", "inline", "inmate", "innocent", "inorganic", "input", "inquest",
        "inroads", "insult", "intended", "inundate", "invoke", "inwardly", "ionic", "irate",
        "iris", "irony", "irritate", "island", "isolated", "issued", "italics", "itches",
        "items", "itinerary", "itself", "ivory", "jabbed", "jackets", "jaded", "jagged",
        "jailed", "jamming", "january", "jargon", "jaunt", "javelin", "jaws", "jazz",
        "jeans", "jeers", "jellyfish", "jeopardy", "jerseys", "jester", "jetting", "jewels",
        "jigsaw", "jingle", "jittery", "jive", "jobs", "jockey", "jogger", "joining",
        "joking", "jolted", "jostle", "journal", "joyous", "jubilee", "judge", "juggled",
        "juicy", "jukebox", "july", "jump", "junk", "jury", "justice", "juvenile",
        "kangaroo", "karate", "keep", "kennel", "kept", "kernels", "kettle", "keyboard",
        "kickoff", "kidneys", "king", "kiosk", "kisses", "kitchens", "kiwi", "knapsack",
        "knee", "knife", "knowledge", "knuckle", "koala", "laboratory", "ladder", "lagoon",
        "lair", "lakes", "lamb", "language", "laptop", "large", "last", "later",
        "launching", "lava", "lawsuit", "layout", "lazy", "lectures", "ledge", "leech",
        "left", "legion", "leisure", "lemon", "lending", "leopard", "lesson", "lettuce",
        "lexicon", "liar", "library", "licks", "lids", "lied", "lifestyle", "light",
        "likewise", "lilac", "limits", "linen", "lion", "lipstick", "liquid", "listen",
        "lively", "loaded", "lobster", "locker", "lodge", "lofty", "logic", "loincloth",
        "long", "looking", "lopped", "lordship", "losing", "lottery", "loudly", "love",
        "lower", "loyal", "lucky", "luggage", "lukewarm", "lullaby", "lumber", "lunar",
        "lurk", "lush", "luxury", "lymph", "lynx", "lyrics", "macro", "madness",
        "magically", "mailed", "major", "makeup", "malady", "mammal", "maps", "masterful",
        "match", "maul", "maverick", "maximum", "mayor", "maze", "meant", "mechanic",
        "medicate", "meeting", "megabyte", "melting", "memoir", "menu", "merger", "mesh",
        "metro", "mews", "mice", "midst", "mighty", "mime", "mirror", "misery",
        "mittens", "mixture", "moat", "mobile", "mocked", "mohawk", "moisture", "molten",
        "moment", "money", "moon", "mops", "morsel", "mostly", "motherly", "mouth",
        "movement", "mowing", "much", "muddy", "muffin", "mugged", "mullet", "mumble",
        "mundane", "muppet", "mural", "musical", "muzzle", "myriad", "mystery", "myth",
        "nabbing", "nagged", "nail", "names", "nanny", "napkin", "narrate", "nasty",
        "natural", "nautical", "navy", "nearby", "necklace", "needed", "negative", "neither",
        "neon", "nephew", "nerves", "nestle", "network", "neutral", "never", "newt",
        "nexus", "nibs", "niche", "niece", "nifty", "nightly", "nimbly", "nineteen",
        "nirvana", "nitrogen", "nobody", "nocturnal", "nodes", "noises", "nomad", "noodles",
        "northern", "nostril", "noted", "nouns", "novelty", "nowhere", "nozzle", "nuance",
        "nucleus", "nudged", "nugget", "nuisance", "null", "number", "nuns", "nurse",
        "nutshell", "nylon", "oaks", "oars", "oasis", "oatmeal", "obedient", "object",
        "obliged", "obnoxious", "observant", "obtains", "obvious", "occur", "ocean", "october",
        "odds", "odometer", "offend", "often", "oilfield", "ointment", "okay", "older",
        "olive", "olympics", "omega", "omission", "omnibus", "onboard", "oncoming", "oneself",
        "ongoing", "onion", "online", "onslaught", "onto", "onward", "oozed", "opacity",
        "opened", "opposite", "optical", "opus", "orange", "orbit", "orchid", "orders",
        "organs", "origin", "ornament", "orphans", "oscar", "ostrich", "otherwise", "otter",
        "ouch", "ought", "ounce", "ourselves", "oust", "outbreak", "oval", "oven",
        "owed", "owls", "owner", "oxidant", "oxygen", "oyster", "ozone", "pact",
        "paddles", "pager", "pairing", "palace", "pamphlet", "pancakes", "paper", "paradise",
        "pastry", "patio", "pause", "pavements", "pawnshop", "payment", "peaches", "pebbles",
        "peculiar", "pedantic", "peeled", "pegs", "pelican", "pencil", "people", "pepper",
        "perfect", "pests", "petals", "phase", "pheasants", "phone", "phrases", "physics",
        "piano", "picked", "pierce", "pigment", "piloted", "pimple", "pinched", "pioneer",
        "pipeline", "pirate", "pistons", "pitched", "pivot", "pixels", "pizza", "playful",
        "pledge", "pliers", "plotting", "plus", "plywood", "poaching", "pockets", "podcast",
        "poetry", "point", "poker", "polar", "ponies", "pool", "popular", "portents",
        "possible", "potato", "pouch", "poverty", "powder", "pram", "present", "pride",
        "problems", "pruned", "prying", "psychic", "public", "puck", "puddle", "puffin",
        "pulp", "pumpkins", "punch", "puppy", "purged", "push", "putty", "puzzled",
        "pylons", "pyramid", "python", "queen", "quick", "quote", "rabbits", "racetrack",
        "radar", "rafts", "rage", "railway", "raking", "rally", "ramped", "randomly",
        "rapid", "rarest", "rash", "rated", "ravine", "rays", "razor", "react",
        "rebel", "recipe", "reduce", "reef", "refer", "regular", "reheat", "reinvest",
        "rejoices", "rekindle", "relic", "remedy", "renting", "reorder", "repent", "request",
        "reruns", "rest", "return", "reunion", "revamp", "rewind", "rhino", "rhythm",
        "ribbon", "richly", "ridges", "rift", "rigid", "rims", "ringing", "riots",
        "ripped", "rising", "ritual", "river", "roared", "robot", "rockets", "rodent",
        "rogue", "roles", "romance", "roomy", "roped", "roster", "rotate", "rounded",
        "rover", "rowboat", "royal", "ruby", "rudely", "ruffled", "rugged", "ruined",
        "ruling", "rumble", "runway", "rural", "rustled", "ruthless", "sabotage", "sack",
        "sadness", "safety", "saga", "sailor", "sake", "salads", "sample", "sanity",
        "sapling", "sarcasm", "sash", "satin", "saucepan", "saved", "sawmill", "saxophone",
        "sayings", "scamper", "scenic", "school", "science", "scoop", "scrub", "scuba",
        "seasons", "second", "sedan", "seeded", "segments", "seismic", "selfish", "semifinal",
        "sensible", "september", "sequence", "serving", "session", "setup", "seventh", "sewage",
        "shackles", "shelter", "shipped", "shocking", "shrugged", "shuffled", "shyness", "siblings",
        "sickness", "sidekick", "sieve", "sifting", "sighting", "silk", "simplest", "sincerely",
        "sipped", "siren", "situated", "sixteen", "sizes", "skater", "skew", "skirting",
        "skulls", "skydive", "slackens", "sleepless", "slid", "slower", "slug", "smash",
        "smelting", "smidgen", "smog", "smuggled", "snake", "sneeze", "sniff", "snout",
        "snug", "soapy", "sober", "soccer", "soda", "software", "soggy", "soil",
        "solved", "somewhere", "sonic", "soothe", "soprano", "sorry", "southern", "sovereign",
        "sowed", "soya", "space", "speedy", "sphere", "spiders", "splendid", "spout",
        "sprig", "spud", "spying", "square", "stacking", "stellar", "stick", "stockpile",
        "strained", "stunning", "stylishly", "subtly", "succeed", "suddenly", "suede", "suffice",
        "sugar", "suitcase", "sulking", "summon", "sunken", "superior", "surfer", "sushi",
        "suture", "swagger", "swept", "swiftly", "sword", "swung", "syllabus", "symptoms",
        "syndrome", "syringe", "system", "taboo", "tacit", "tadpoles", "tagged", "tail",
        "taken", "talent", "tamper", "tanks", "tapestry", "tarnished", "tasked", "tattoo",
        "taunts", "tavern", "tawny", "taxi", "teardrop", "technical", "tedious", "teeming",
        "tell", "template", "tender", "tepid", "tequila", "terminal", "testing", "tether",
        "textbook", "thaw", "theatrics", "thirsty", "thorn", "threaten", "thumbs", "thwart",
        "ticket", "tidy", "tiers", "tiger", "tilt", "timber", "tinted", "tipsy",
        "tirade", "tissue", "titans", "toaster", "tobacco", "today", "toenail", "toffee",
        "together", "toilet", "token", "tolerant", "tomorrow", "tonic", "toolbox", "topic",
        "torch", "tossed", "total", "touchy", "towel", "toxic", "toyed", "trash",
        "trendy", "tribal", "trolling", "truth", "trying", "tsunami", "tubes", "tucks",
        "tudor", "tuesday", "tufts", "tugs", "tuition", "tulips", "tumbling", "tunnel",
        "turnip", "tusks", "tutor", "tuxedo", "twang", "tweezers", "twice", "twofold",
        "tycoon", "typist", "tyrant", "ugly", "ulcers", "ultimate", "umbrella", "umpire",
        "unafraid", "unbending", "uncle", "under", "uneven", "unfit", "ungainly", "unhappy",
        "union", "unjustly", "unknown", "unlikely", "unmask", "unnoticed", "unopened", "unplugs",
        "unquoted", "unrest", "unsafe", "until", "unusual", "unveil", "unwind", "unzip",
        "upbeat", "upcoming", "update", "upgrade", "uphill", "upkeep", "upload", "upon",
        "upper", "upright", "upstairs", "uptight", "upwards", "urban", "urchins", "urgent",
        "usage", "useful", "usher", "using", "usual", "utensils", "utility", "utmost",
        "utopia", "uttered", "vacation", "vague", "vain", "value", "vampire", "vane",
        "vapidly", "vary", "vastness", "vats", "vaults", "vector", "veered", "vegan",
        "vehicle", "vein", "velvet", "venomous", "verification", "vessel", "veteran", "vexed",
        "vials", "vibrate", "victim", "video", "viewpoint", "vigilant", "viking", "village",
        "vinegar", "violin", "vipers", "virtual", "visited", "vitals", "vivid", "vixen",
        "vocal", "vogue", "voice", "volcano", "vortex", "voted", "voucher", "vowels",
        "voyage", "vulture", "wade", "waffle", "wagtail", "waist", "waking", "wallets",
        "wanted", "warped", "washing", "water", "waveform", "waxing", "wayside", "weavers",
        "website", "wedge", "weekday", "weird", "welders", "went", "wept", "were",
        "western", "wetsuit", "whale", "when", "whipped", "whole", "wickets", "width",
        "wield", "wife", "wiggle", "wildly", "winter", "wipeout", "wiring", "wise",
        "withdrawn", "wives", "wizard", "wobbly", "woes", "woken", "wolf", "womanly",
        "wonders", "woozy", "worry", "wounded", "woven", "wrap", "wrist", "wrong",
        "yacht", "yahoo", "yanks", "yard", "yawning", "yearbook", "yellow", "yesterday",
        "yeti", "yields", "yodel", "yoga", "younger", "yoyo", "zapped", "zeal",
        "zebra", "zero", "zesty", "zigzags", "zinger", "zippers", "zodiac", "zombie",
        "zones", "zoom"
    )

    /**
     * Main conversion function following Cake Wallet's approach
     * @param bip39Mnemonic 12, 18, or 24 word BIP39 mnemonic
     * @param accountIndex Account index for derivation (default: 0)
     * @param passphrase Optional BIP39 passphrase (default: empty)
     * @return 25-word Monero legacy mnemonic or null if conversion fails
     */
    fun getLegacySeedFromBip39(
        bip39Mnemonic: List<String>,
        passphrase: String = "",
        accountIndex: Int = 0
    ): List<String>? {
        return try {
            if (bip39Mnemonic.size !in listOf(12, 18, 24)) return null

            // Step 1: Generate BIP39 seed
            val seed = Mnemonic().toSeed(bip39Mnemonic, passphrase)

            // Step 2: Derive BIP32 key at m/44'/128'/accountIndex'/0/0
            val hdWallet = HDWallet(seed, 128, HDWallet.Purpose.BIP44)
            val privateKey = hdWallet.privateKey("m/44'/128'/$accountIndex'/0/0").privKey

            // Step 3: Reduce private key with Ed25519 curve order (Cake Wallet approach)
            val spendKey = reduceECKey(privateKey.toByteArray32())

            // Step 4: Encode as Monero legacy mnemonic
            encodeMoneroMnemonic(spendKey)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun BigInteger.toByteArray32(): ByteArray {
        val bytes = this.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.sliceArray(1..32)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> throw IllegalArgumentException("BigInteger exceeds 32 bytes: ${bytes.size}")
        }
    }

    /**
     * Reduce EC key with Ed25519 curve order (Cake Wallet approach)
     * This is the key difference from Ledger - no Keccak hashing!
     */
    private fun reduceECKey(buffer: ByteArray): ByteArray {
        val bigNumber = readBytesLittleEndian(buffer)
        val result = bigNumber.mod(ED25519_CURVE_ORDER)

        // Convert back to little-endian 32-byte array
        val resultBuffer = ByteArray(32)
        var remainder = result
        for (i in 0 until 32) {
            resultBuffer[i] = (remainder.and(BigInteger.valueOf(0xff))).toByte()
            remainder = remainder.shiftRight(8)
        }
        return resultBuffer
    }

    /**
     * Read BigInt from little-endian byte array (Cake Wallet approach)
     */
    private fun readBytesLittleEndian(bytes: ByteArray): BigInteger {
        fun read(start: Int, end: Int): BigInteger {
            if (end - start <= 4) {
                var result = 0L
                for (i in end - 1 downTo start) {
                    result = result * 256 + (bytes[i].toInt() and 0xff)
                }
                return BigInteger.valueOf(result)
            }
            val mid = start + ((end - start) shr 1)
            return read(start, mid) + read(mid, end) * (BigInteger.ONE.shiftLeft((mid - start) * 8))
        }
        return read(0, bytes.size)
    }

    /**
     * Encode Monero mnemonic from spend key (following Monero's algorithm)
     */
    private fun encodeMoneroMnemonic(spendKey: ByteArray): List<String> {
        if (spendKey.size != 32) {
            throw IllegalArgumentException("Spend key must be 32 bytes")
        }

        val words = mutableListOf<String>()

        // Process spend key in 4-byte chunks, generating 3 words per chunk
        for (i in 0 until spendKey.size / 4) {
            // Read 4 bytes as little-endian uint32
            val val32 = ((spendKey[i * 4 + 0].toInt() and 0xff) shl 0) or
                    ((spendKey[i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((spendKey[i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((spendKey[i * 4 + 3].toInt() and 0xff) shl 24)

            // Convert to unsigned long to handle large values
            val val32Long = val32.toLong() and 0xFFFFFFFFL

            // Generate 3 words using Monero's algorithm
            val w1 = val32Long % MONERO_WORDLIST.size
            val w2 = ((val32Long / MONERO_WORDLIST.size) + w1) % MONERO_WORDLIST.size
            val w3 = (((val32Long / MONERO_WORDLIST.size) / MONERO_WORDLIST.size) + w2) % MONERO_WORDLIST.size

            words.add(MONERO_WORDLIST[w1.toInt()])
            words.add(MONERO_WORDLIST[w2.toInt()])
            words.add(MONERO_WORDLIST[w3.toInt()])
        }

        // Calculate CRC32 checksum on trimmed words (first 3 characters)
        val trimmedWords = StringBuilder()
        for (word in words) {
            trimmedWords.append(word.substring(0, minOf(MONERO_PREFIX_LENGTH, word.length)))
        }

        val crc32 = CRC32()
        crc32.update(trimmedWords.toString().toByteArray(StandardCharsets.UTF_8))
        val checksum = crc32.value

        // Add checksum word (25th word) - selected from first 24 words
        val checksumWordIndex = (checksum % 24).toInt()
        words.add(words[checksumWordIndex])

        return words
    }

}
