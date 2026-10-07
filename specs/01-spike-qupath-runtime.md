# 01 — Spike: QuPath 0.7 Runtime (GATE)

**Depends on:** 00. **Blocks:** everything else. Throwaway code; output is a findings note, not product code.

## Questions to answer

| Q | Question | Why it matters |
|---|----------|----------------|
| Q1 | What Java version does QuPath 0.7.x bundle? | Must be ≥11 for the tools (A3). |
| Q2 | Is the extension API (`QuPathExtension`, menu registration, prefs) unchanged from 0.6? Which Gradle plugin/template version targets 0.7? | Shapes 06, 08, 10. |
| Q3 | How do we locate the bundled `java.exe` from inside QuPath? (`System.getProperty("java.home")` + `bin\java.exe`?) Does a jlink'd runtime include `java.exe`? | Core of A4. |
| Q4 | Does QuPath's extension loader scan jars **nested** inside an extension jar, or loose jars in subfolders of the extensions dir? | Confirms 02's nested+extract design keeps Bio-Formats 8.5 off QuPath's classpath. |
| Q5 | Do both tools run correctly when launched with QuPath's `java.exe` (incl. `--add-opens`)? Does blosc's native DLL load? | Validates A4 end-to-end. |
| Q6 | Does CLI-produced OME-TIFF open in QuPath 0.7 with **correct fluorescence channels**? | Confirms the whole premise (A1) still holds on 0.7. |
| Q7 | Does the defect still exist in QuPath 0.7 when opening `.mrxs` directly? | If fixed upstream, revisit the project. |
| Q8 | Peak memory of each tool step on the user's largest samples; minimum `-Xmx` that succeeds. | Sets the default heap (E3, spec 06). |
| Q9 | Default tile size and `max_workers` reported by each tool's `--help`. | Fills spec 06 defaults (D1). |
| Q10 | TIFF-size/input ratio for each non-default compression (esp. Uncompressed) on both samples. | Sets `OUTPUT_FACTOR` per compression in spec 07. |

## Method

1. Install QuPath 0.7.x (Windows). Record version + `java -version` of its runtime.
2. Run both tools by hand with QuPath's `java.exe` on one user fluorescence sample.
3. Open the output and the raw `.mrxs` in 0.7; screenshot channel lists.
4. Build a hello-world extension (menu item that launches `java -version` as a child process, logs output).
5. Drop a jar containing a nested `.jar` resource into the extensions dir; check QuPath's classloader/logs.

## Exit criteria

- Written answers to Q1–Q7 in `specs/01-findings.md`.
- Go / no-go on 0.7. If no-go, record the fallback (0.6.x) and the reason; update A3 in spec 00.
- Any assumption in specs 02–10 that is invalidated is flagged and the spec revised **before** build starts.
