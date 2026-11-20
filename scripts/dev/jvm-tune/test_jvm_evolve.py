#!/usr/bin/env python3
"""Unit tests for jvm_evolve.py: flag rendering, parsers, the GA's selection rules and the workdir guard.

Run: python3 -m unittest discover -s scripts/dev/jvm-tune -p 'test_*.py'
No JVM is started: the GA is tested with a fake scorer.
"""

import random
import tempfile
import unittest
from pathlib import Path

import jvm_evolve as je


class RenderFlagsTest(unittest.TestCase):
    def test_baseline_genome_renders_the_production_gene_flags(self):
        flags = je.render_flags(je.Genome.of(je.BASELINE), ["-Dx=1"])
        self.assertEqual(flags, ["-Dx=1", "-XX:+UseG1GC", "-Xmx2g", "-Xms64m"])

    def test_empty_gene_values_are_omitted(self):
        g = je.Genome.of({"gc": "Z", "xmx": "3g", "xms": "0", "xss": "512k", "codecache": "128m"})
        flags = je.render_flags(g, [])
        self.assertEqual(
            flags,
            ["-XX:+UseZGC", "-Xmx3g", "-Xss512k", "-XX:ReservedCodeCacheSize=128m"],
        )

    def test_gc_flag_for_every_choice(self):
        for choice in je.SEARCH_SPACE["gc"]:
            flags = je.render_flags(je.Genome.of({**je.BASELINE, "gc": choice}), [])
            self.assertIn(je.GC_FLAG[choice], flags)

    def test_base_flags_drop_the_genes_and_the_fr_options(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "jvm-flags.txt"
            p.write_text(
                "# comment\n-Xms64m\n-Xmx2g\n-XX:+ExitOnOutOfMemoryError\n"
                "-XX:FlightRecorderOptions=x\n\n-Dstdout.encoding=UTF-8\n",
                encoding="utf-8",
            )
            self.assertEqual(je.load_base_flags(p), ["-XX:+ExitOnOutOfMemoryError", "-Dstdout.encoding=UTF-8"])

    def test_repo_flag_file_loads_and_has_no_gene_flags(self):
        base = je.load_base_flags()
        self.assertIn("-XX:+ExitOnOutOfMemoryError", base)
        self.assertFalse(any(f.startswith(("-Xms", "-Xmx")) for f in base))


class ParserTest(unittest.TestCase):
    def test_gc_pauses_are_summed_from_pause_lines_only(self):
        log = (
            "[0.100s][info][gc] Using G1\n"
            "[1.200s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 12M->3M(64M) 4.500ms\n"
            "[2.000s][info][gc] GC(1) Pause Young (Concurrent Start) 20M->5M(64M) 1.500ms\n"
            "[2.100s][info][gc] GC(1) Concurrent Mark 9.000ms\n"
        )
        self.assertAlmostEqual(je.parse_gc_pauses_ms(log), 6.0)

    def test_time_line_is_the_last_matching_line(self):
        err = "some java output\nWARN noise\n0.45 123456\n"
        self.assertEqual(je.parse_time_line(err), (0.45, 123456))

    def test_time_line_missing(self):
        self.assertIsNone(je.parse_time_line("no timing here\n"))

    def test_fitness_weights_wall_rss_and_gc(self):
        # 2 GiB peak RSS -> 2 * 0.5 = 1.0; 1500 ms of GC -> 1.5 s; wall 3.0 s. Total 5.5.
        self.assertAlmostEqual(je.fitness_of(3.0, 2 * 1024 * 1024, 1500.0), 5.5)


class GeneticAlgorithmTest(unittest.TestCase):
    @staticmethod
    def fake_fitness(g: je.Genome) -> float:
        # Lower is better: every gene that differs from the baseline costs 1.0, so the optimum is the baseline.
        v = g.values()
        return sum(1.0 for k, choices in je.SEARCH_SPACE.items() if v[k] != je.BASELINE[k])

    def test_generation_zero_contains_the_baseline_and_no_duplicates(self):
        pop = je.initial_population(random.Random(3), 8)
        self.assertEqual(pop[0], je.Genome.of(je.BASELINE))
        self.assertEqual(len({g.key() for g in pop}), len(pop))
        self.assertEqual(len(pop), 8)

    def test_elitism_keeps_the_best_and_the_best_never_regresses(self):
        rng = random.Random(7)

        def scorer(g, gen):
            return je.Score(g, self.fake_fitness(g), generation=gen)

        best, history = je.evolve(rng, population=6, generations=6, mutation_rate=0.3, scorer=scorer, log=lambda *_: None)
        bests = [gen[0].fitness for gen in history]
        self.assertEqual(bests, sorted(bests, reverse=True), "the best fitness of a generation never gets worse")
        self.assertEqual(best[0].fitness, min(bests))

    def test_search_finds_the_optimum_of_a_separable_fitness(self):
        rng = random.Random(11)

        def scorer(g, gen):
            return je.Score(g, self.fake_fitness(g), generation=gen)

        best, _ = je.evolve(rng, population=10, generations=8, mutation_rate=0.3, scorer=scorer, log=lambda *_: None)
        self.assertEqual(best[0].fitness, 0.0)
        self.assertEqual(best[0].genome.values(), je.BASELINE)

    def test_cache_scores_a_repeated_genome_once(self):
        calls = []

        def scorer(g, gen):
            calls.append(g.key())
            return je.Score(g, self.fake_fitness(g), generation=gen)

        je.evolve(random.Random(5), population=4, generations=4, mutation_rate=0.0, scorer=scorer, log=lambda *_: None)
        self.assertEqual(len(calls), len(set(calls)), "no genome is timed twice")

    def test_a_failed_genome_never_wins(self):
        rng = random.Random(2)

        def scorer(g, gen):
            if g == je.Genome.of(je.BASELINE):
                return je.Score(g, float("inf"), error="boom", generation=gen)
            return je.Score(g, 1.0, generation=gen)

        best, _ = je.evolve(rng, population=4, generations=2, mutation_rate=0.2, scorer=scorer, log=lambda *_: None)
        self.assertNotEqual(best[0].genome, je.Genome.of(je.BASELINE))


class WorkdirGuardTest(unittest.TestCase):
    def test_refuses_a_live_unidrive_directory(self):
        with self.assertRaises(SystemExit):
            je.check_workdir(je.FORBIDDEN_WORKDIRS[0])

    def test_refuses_a_non_empty_foreign_directory(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "keep.txt").write_text("x", encoding="utf-8")
            with self.assertRaises(SystemExit):
                je.check_workdir(Path(d))

    def test_accepts_an_empty_directory_and_marks_it(self):
        with tempfile.TemporaryDirectory() as d:
            work = Path(d) / "run"
            je.check_workdir(work)
            self.assertTrue((work / je.WORKDIR_MARKER).exists())
            je.check_workdir(work)  # a marked directory is reusable


if __name__ == "__main__":
    unittest.main()
