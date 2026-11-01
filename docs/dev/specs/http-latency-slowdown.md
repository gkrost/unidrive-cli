# Latency-driven slowdown of an HTTP budget: measurements and proposal

`HttpRetryBudget` reacts to refusals: four 429/503 within 20 s open its circuit and halve its permits, and five
quiet minutes give one permit back. The idea measured here reacts earlier: when the provider's response times rise
well above their own recent level, hand out one permit less, before the provider refuses, and give it back once the
latency is calm again. It would never hand out more than the budget's `maxConcurrency`, so no configured limit (the
Internxt walk's included) changes.

**Verdict: not a clear win; not implemented.** In a simulation calibrated to the Internxt Drive listings, the
slowdown ends the refusals in the overload runs and costs nothing while the provider copes. But the parameters that
end them reliably at 2 permits (the Internxt Drive budget) cost 4-21 % of the throughput whenever the provider is
slow for reasons the client does not cause, and the cheaper parameters miss a slowly building overload in one run of
three. Whether either trade is worth it depends on where Internxt starts refusing, which nobody has seen: no 429 is
on record for the Drive listings. The live checks at the end would decide it. The same runs found a cheaper lever for
the case that matters, the storm threshold of a 2-permit budget (see *A cheaper lever*).

## The rule that was measured

The prototype is `LatencyController` in the test sources of `core/app/core`.

- **Samples:** the time of one successful request from its start on the wire to its answer. A refused or failed
  request is no sample (its quick answer would pull the median down).
- **Window:** `windowSize` samples; windows do not overlap. Each full window gives one median and one decision.
- **Baseline:** the `baselineQuantile` of the last `baselineWindows` window medians.
- **Step down:** a window median above `baseline × riseRatio` takes `stepDown` permits away, never below `floor`.
- **Step up:** `recoverWindows` windows in a row with a median at most `baseline × recoverRatio` give one permit back,
  up to `maxConcurrency`, and only when no throttle was recorded in the last 60 s. A window between the two ratios
  restarts that count.
- **Step check (optional):** the window after a step down must show a median at least `stepCheckDrop` lower than the
  one that caused the step; if not, the limit goes back to what it was before the step (all of it, and also while a
  throttle is recent: a rollback is not growth) and that median becomes the whole baseline.
- A slowdown the client causes falls once it sends less, so the low baseline stays and the step holds. A slowdown it
  does not cause (the provider is slower for everyone) becomes the baseline once the fast windows have left the
  history, and the permits come back; that delay is what the slowdown costs.

## Proposed parameters, should it be built

| Parameter | Value | Why |
|---|---|---|
| `windowSize` | 40 | At the Internxt spread (p99 about ten times the p50) a median of 10 or 20 trips on noise: with rise 2.0 the healthy and heavy-folder runs lost 29-68 % at windows of 10, 2-12 % at 20, at most 0.6 % at 40 (seed 1). A window is about 12 s of listings at 2 permits. |
| `baselineQuantile` | 0.25 | The lowest of the recent medians is biased low by the spread: as the baseline (windows of 20, rise 2.0) it cost 38-73 % of a healthy provider's throughput at 2-8 permits, the lower quartile 2-5 % (seed 1). |
| `riseRatio` | 2.5 | 2.0 tripped on the heavy-folder runs at windows of 20 (2-12 % lost); 3.0 with a history of 5 let twice as many refusals through at 2 permits (108 against 54 in crowded-30s, three seeds). |
| `recoverRatio`, `recoverWindows` | 1.3, 3 | Three calm windows (about 40 s at 2 permits) before a permit comes back; with 2, refusals got through at 4 permits (8 against 0) and the crowded runs at 2 permits lost 4 % (history 5, three seeds). |
| `baselineWindows` | 5 or 10: the open trade | 5 missed the slowly building overload at 2 permits in one run of three (as many refusals as today) and cost 0.6-8 % where the provider was slower for everyone. 10 ended every refusal but cost 4-21 % there, and 5-10 % in the crowded runs at 4 and 8 permits. |
| `stepCheckDrop` | none | A check of 15 % with a history of 10 cut the cost of the slower runs to 0.1-5 %, but at 4 and 8 permits took the step back in the crowded runs, which then got as many refusals as today. |
| `stepDown`, `floor` | 1, 1 | The Drive budget has 2 permits: one step is the whole range. |
| ceiling | `maxConcurrency` | Unchanged: 2 for the Internxt Drive listings. |

## How the numbers were measured

No live account: `LatencySimulation` drives a real `HttpRetryBudget` in virtual time against a simulated provider,
the way the Internxt heavy listings use it (permit, slot, request, success bookkeeping, a refusal recorded as a
throttle and waited out outside the permit; twice as many callers as permits). With the prototype, a caller also
waits in front of the permit until fewer than the controller's limit of its requests are in flight, which is what
lowering the budget's own count would do.

The provider's latency at `n` requests in flight (the client's and other traffic) is
`360 ms × (1 + 0.07 × (n - 1))` with a log-normal spread of sigma 0.99 (mean 590 ms, p50 about 0.36 s, p99 about
3.6 s). That fits the only load curve on record for the Internxt Drive listings, a walk of 127 folder listings that
took 74.5 s at 1 in flight, 40.4 s at 2, 18-27 s at 4 and 14.5 s at 8 (the model gives 75 s, 40 s, 23 s and 14 s),
and the recorded per-call spread (p50 about 0.3 s, p99 2-4 s). Where the provider starts refusing is an assumption:
the crowded scenarios put a knee at twice the client's permits (each request past it adds the whole base latency
again) and refuse above three times.

Five scenarios of 20 virtual minutes, each at 2, 4 and 8 permits and with three random seeds:

- **healthy:** the calibrated provider alone. The slowdown must cost nothing here.
- **crowded:** other traffic climbs from 0 to `2 × permits + 1` requests in flight over 4 minutes, holds 8 minutes and
  falls again; at the peak the client's full permits are refused, half of them are only slow. Retry-After 5 s.
- **crowded-30s:** the same with Retry-After 30 s (Internxt's waits are clamped to 0.5-60 s).
- **slower:** for 8 minutes the provider is three times slower for everyone; sending less does not help.
- **heavy-mix:** one request in 50 is thirty times as expensive whatever the load (the big folders of a walk).

### Results

Sums over three seeds: ok is the successful requests in 3 × 20 minutes, refused the 429 answers. The proposed sets
are windows of 40, the lower-quartile baseline, rise 2.5, recover 1.3 for 3 windows, with the history and step check
named in the column; each cell is the change in ok against today's budget and the refusals.

| permits | scenario | today: ok / refused | history 5 | history 10 | history 10, check 15 % |
|---|---|---|---|---|---|
| 2 | healthy | 11476 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 2 | heavy-mix | 7121 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 2 | crowded | 5589 / 24 | -0.2 % / 8 | -1.4 % / 0 | -1.4 % / 0 |
| 2 | crowded-30s | 5602 / 162 | -0.5 % / 54 | -1.6 % / 0 | -1.6 % / 0 |
| 2 | slower | 8334 / 0 | -7.6 % / 0 | -12.0 % / 0 | -4.8 % / 0 |
| 4 | healthy | 20436 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 4 | heavy-mix | 12880 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 4 | crowded | 9434 / 12 | -0.4 % / 0 | -9.9 % / 0 | 0 % / 12 |
| 4 | crowded-30s | 9370 / 12 | +0.3 % / 0 | -9.3 % / 0 | +0.2 % / 12 |
| 4 | slower | 14995 / 0 | -2.6 % / 0 | -21.1 % / 0 | -3.5 % / 0 |
| 8 | healthy | 33063 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 8 | heavy-mix | 20887 / 0 | 0 % / 0 | 0 % / 0 | 0 % / 0 |
| 8 | crowded | 13167 / 12 | +8.1 % / 0 | -5.5 % / 0 | +0.2 % / 12 |
| 8 | crowded-30s | 13122 / 12 | +8.5 % / 0 | -5.2 % / 0 | +0.2 % / 12 |
| 8 | slower | 24214 / 0 | -0.6 % / 0 | -4.2 % / 0 | -0.1 % / 0 |

The 8 and 54 refusals of history 5 at 2 permits all fall in one seed, where the controller never stepped: the
overload built up slowly enough for 5 windows of history to take it into the baseline. The other two seeds had none.

What this says:

- **Today's budget at 2 permits does not stop a refusing provider.** Its storm breaker needs four refusals within
  20 s; with 2 permits and a long Retry-After they never come that fast, so in crowded-30s the breaker never opened
  (the cap stayed at 2.00 permits all run, while only 1.55 requests were on the wire on average: the rest of the time
  the callers sat out Retry-After) and the client was refused 54 times per run. That is the Internxt Drive case: 2 permits and waits of up to 60 s.
- **The slowdown ends those refusals** in every crowded run with a history of 10, and in two seeds of three with 5.
- **Throughput is not the gain.** Where the provider is crowded the client moves about as much as today; it moves it
  without refusals and their retries.
- **It costs nothing when the provider copes**, the heavy folders included.
- **It costs when the provider is slow for everyone,** and the cost grows with the history that makes it reliable.
  The step check takes most of that cost away but lets refusals through at 4 permits: past the knee the client's own
  step lowers the latency too little for the check to see, although it is what keeps the client under the refusal
  limit. Latency alone cannot tell a provider that is slow from one that is about to refuse.

## A cheaper lever: the storm threshold of a small budget

The refusals the slowdown would prevent at 2 permits come from today's storm breaker not opening: it needs four
throttles within 20 s, and two permits with a long Retry-After never deliver them that fast. The same harness with
the budget's `stormThreshold` at 2 instead of 4, no latency control, three seeds, 2 permits:

| scenario | threshold 4 (today): ok / refused | threshold 2: ok / refused |
|---|---|---|
| crowded | 5589 / 24 | 5582 / 12 |
| crowded-30s | 5602 / 162 | 5519 / 12 |
| healthy, heavy-mix, slower | unchanged (no throttle) | unchanged |

It cuts the refusals of crowded-30s by 93 % for 1.5 % of the throughput and costs nothing where nothing is refused,
because it only acts on refusals. It does not act before the first refusals, as the latency slowdown would, but it
needs no latency model and no live calibration. A threshold that follows the permits (for example
`min(4, maxConcurrency)`) is a one-line change with a test like `HttpRetryBudgetPermitTest`; it is not part of this
work and wants its own decision.

## Rerun

```bash
cd core
UNIDRIVE_LATENCY_SWEEP=true UNIDRIVE_LATENCY_GRID=final UNIDRIVE_LATENCY_H=5,10 UNIDRIVE_LATENCY_CHECK=none,0.15 \
UNIDRIVE_LATENCY_SEEDS=1,2,3 ./gradlew --no-daemon :app:core:test --tests 'org.krost.unidrive.http.LatencySweep' --rerun
```

The table lands in `core/app/core/build/latency-sweep-<grid>.tsv`, one row per seed, permits, scenario and parameter
set. `meanCap` is the bound the client ran under (the budget's permits, or the controller's limit when lower, sampled
each second); `meanInFlight` is the time-weighted mean of the client's requests actually on the wire. They part where
callers wait out a refusal or a permit. The grid: `UNIDRIVE_LATENCY_W`, `_H`, `_Q`, `_RISE`, `_REC`, `_X`, `_CHECK` (`none` for no step check), and
`_CEILINGS`, `_SCENARIOS`, `_SEEDS`, `_THREADS` (default 3); each takes a comma-separated list, defaulting to the
proposal. `UNIDRIVE_LATENCY_STORM` sets the budget's storm threshold for the whole run (the cheaper-lever table:
`UNIDRIVE_LATENCY_STORM=2 UNIDRIVE_LATENCY_W= UNIDRIVE_LATENCY_CEILINGS=2`, an empty list leaving only today's
budget). Without `UNIDRIVE_LATENCY_SWEEP=true` the sweep does nothing, so `check` does not run it.

## What a live check on a test account should settle

1. **Does the provider refuse the listings at all, and where?** Two, three and four listings in flight on a test
   account (the walk with its permits raised in a test build) until a 429 or 503 comes, or not. No refusal up to a few
   times today's 2 means the slowdown has nothing to prevent.
2. **How does the median move with load?** The request metrics (`total_ms` p50 of the listing classes per minute) at
   1, 2 and 4 listings in flight: the model expects +7 % per further listing. A steep rise before the refusals is the
   signal the slowdown needs; a flat curve up to a sudden refusal means it would not see the refusal coming.
3. **How noisy is a quiet account?** The p50 of 40 listings, window after window, on a quiet account: the spread
   decides whether 2.5 x the lower quartile stays clear of noise, as it did in the model.
4. **How often is the provider slow for everyone?** Periods where the listing median doubles while unidrive's own load
   does not change (other times of day, other accounts): each one is what the slowdown pays for.
