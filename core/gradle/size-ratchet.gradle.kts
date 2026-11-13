// Size ratchet (gkrost/unidrive-cli#534, item I): the big files and the long functions may not grow.
//
//   sizeRatchet       the gate task (`check` depends on it). Fails when
//                       - a source file in the baseline has more lines than recorded,
//                       - a function in the baseline has more lines than recorded,
//                       - a file NOT in the baseline reaches FILE_MIN lines, or a function NOT in the baseline
//                         reaches FUN_MIN lines (a new giant is a new entry, and a new entry needs a reason),
//                       - with -PsizeRatchetBase=<git ref>: a baseline value is higher than the ref's baseline
//                         (the baseline only goes down; a PR that raises it shows up here).
//   sizeRatchetLower  rewrites config/size-ratchet/baseline.txt DOWN only: lowers every recorded value that
//                     shrank, drops entries that fell under the thresholds, never raises or adds one.
//
// Measured in lines of the file as it stands (wc -l). A function spans from its `fun` line to the first later
// line that is the closing `}` at the same indent; expression-bodied functions are not measured. The key of a
// function is "fun:<path>::<name>#<n>", n counting same-named functions in the file in source order. Main
// sources only (**/src/main/**/*.kt under core/).
val sizeRatchetFileMin = 1000 // detekt LargeClass threshold
val sizeRatchetFunMin = 120 // detekt LongMethod threshold
val sizeRatchetBaseline = layout.projectDirectory.file("config/size-ratchet/baseline.txt").asFile

fun sizeRatchetMeasure(root: File): Map<String, Int> {
    val result = sortedMapOf<String, Int>()
    val funStart = Regex("""^(\s*)(?:[A-Za-z@][\w@.()<>, =\"]*\s)?fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?`?(\w+)`?\s*\(""")
    root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
        .forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val lines = file.readLines()
            result["file:$rel"] = lines.size
            val ordinals = mutableMapOf<String, Int>()
            for (i in lines.indices) {
                val m = funStart.find(lines[i]) ?: continue
                val indent = m.groupValues[1].length
                val name = m.groupValues[2]
                val n = ordinals.merge(name, 1, Int::plus)!!
                // Signature: walk to paren depth 0, then require the body to open with `{`.
                var depth = 0
                var j = i
                var bodyOpen = false
                while (j < lines.size) {
                    for (c in lines[j]) {
                        if (c == '(') depth++ else if (c == ')') depth--
                    }
                    if (depth <= 0) {
                        bodyOpen = lines[j].trimEnd().endsWith("{")
                        break
                    }
                    j++
                }
                if (!bodyOpen) continue
                var end = -1
                for (k in j + 1 until lines.size) {
                    val l = lines[k]
                    if (l.isBlank()) continue
                    val ind = l.length - l.trimStart().length
                    if (ind <= indent) {
                        if (ind == indent && l.trim() == "}") end = k
                        break
                    }
                }
                if (end >= 0) result["fun:$rel::$name#$n"] = end - i + 1
            }
        }
    return result
}

fun sizeRatchetParse(text: String): Map<String, Int> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate {
        val at = it.lastIndexOf(' ')
        it.substring(0, at).trim() to it.substring(at + 1).toInt()
    }

tasks.register("sizeRatchet") {
    group = "verification"
    description = "Fails when a baselined file or function grows, or a new one crosses the size thresholds."
    doLast {
        val base = sizeRatchetParse(sizeRatchetBaseline.readText())
        val now = sizeRatchetMeasure(projectDir)
        val problems = mutableListOf<String>()
        for ((key, size) in now) {
            val min = if (key.startsWith("file:")) sizeRatchetFileMin else sizeRatchetFunMin
            val recorded = base[key]
            when {
                recorded == null && size >= min ->
                    problems += "$key has $size lines, not in the baseline (threshold $min): split it, or record it deliberately"
                recorded != null && size > recorded ->
                    problems += "$key grew: $size lines, baseline $recorded (+${size - recorded})"
            }
        }
        val baseRef = providers.gradleProperty("sizeRatchetBase").orNull
        if (baseRef != null) {
            val rel = sizeRatchetBaseline.relativeTo(rootDir.parentFile).invariantSeparatorsPath
            val proc = ProcessBuilder("git", "show", "$baseRef:$rel").directory(rootDir).redirectErrorStream(true).start()
            val old = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() == 0) {
                val oldMap = sizeRatchetParse(old)
                // A deliberate, owner-approved one-time raise (2026-11-13, #534): the PR that ADDS a "# one-time-raise" line to a baseline that has
                // none is let through; once it is on the base ref the line no longer exempts anything, so it cannot be reused.
                val hasRaiseMarker = { text: String -> text.lineSequence().any { it.startsWith("# one-time-raise") } }
                val raiseAllowed = hasRaiseMarker(sizeRatchetBaseline.readText()) && !hasRaiseMarker(old)
                if (raiseAllowed) logger.lifecycle("sizeRatchet: one-time raise accepted (the baseline at $baseRef has no # one-time-raise line).")
                for ((key, v) in oldMap) {
                    val cur = base[key]
                    if (!raiseAllowed && cur != null && cur > v) problems += "baseline raised for $key: $v at $baseRef, $cur here (the baseline only goes down)"
                }
                if (!raiseAllowed) for (key in base.keys - oldMap.keys) problems += "baseline gained $key (the baseline only goes down)"
            } else {
                logger.warn("sizeRatchet: no baseline at $baseRef, comparison skipped")
            }
        }
        val shrunk = base.count { (k, v) -> (now[k] ?: 0) < v }
        if (shrunk > 0) logger.lifecycle("sizeRatchet: $shrunk baseline entries are above the current size; ./gradlew sizeRatchetLower locks the gain in.")
        if (problems.isNotEmpty()) {
            throw GradleException("Size ratchet (gkrost/unidrive-cli#534):\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("sizeRatchet: ${base.size} baselined entries, none grew.")
    }
}

tasks.register("sizeRatchetRecord") {
    group = "verification"
    description = "Seeds config/size-ratchet/baseline.txt from the current sizes (an entry already recorded keeps the smaller of its recorded and current size; a new entry at or over the thresholds is added, which `sizeRatchet -PsizeRatchetBase=<ref>` then flags)."
    doLast {
        val header = sizeRatchetBaseline.readLines().takeWhile { it.startsWith("#") }
        val now = sizeRatchetMeasure(projectDir).filter { (k, v) -> v >= if (k.startsWith("file:")) sizeRatchetFileMin else sizeRatchetFunMin }
        // An entry already recorded never goes up: a key keeps the smaller of its recorded and current size.
        val recorded = sizeRatchetParse(sizeRatchetBaseline.readText())
        val out = now.mapValues { (k, v) -> recorded[k]?.let { minOf(it, v) } ?: v }
        sizeRatchetBaseline.writeText((header + out.map { "${it.key} ${it.value}" }).joinToString("\n") + "\n")
        logger.lifecycle("sizeRatchetRecord: ${out.size} entries.")
    }
}

tasks.register("sizeRatchetLower") {
    group = "verification"
    description = "Lowers config/size-ratchet/baseline.txt to the current sizes; never raises or adds an entry."
    doLast {
        val header = sizeRatchetBaseline.readLines().takeWhile { it.startsWith("#") }
        val base = sizeRatchetParse(sizeRatchetBaseline.readText())
        val now = sizeRatchetMeasure(projectDir)
        val out = sortedMapOf<String, Int>()
        for ((key, recorded) in base) {
            val min = if (key.startsWith("file:")) sizeRatchetFileMin else sizeRatchetFunMin
            val cur = now[key] ?: continue
            if (cur >= min) out[key] = minOf(recorded, cur)
        }
        sizeRatchetBaseline.writeText((header + out.map { "${it.key} ${it.value}" }).joinToString("\n") + "\n")
        logger.lifecycle("sizeRatchetLower: ${out.size} entries (${base.size - out.size} dropped).")
    }
}
