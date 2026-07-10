# Wire protocol — single source of truth

The Open Crafter Link wire format (the `OCLO` telemetry, `OCLI` instruction, and `OCLV`
vision frames) is spoken by two independent codecs:

- **Java (mod):** `src/main/java/mod/kelvinlby/link/BinaryCodec.java`
- **Python (controller):** `pylib/src/ocl/__init__.py`

To stop the two from silently drifting apart, the shared *constants* have one source of
truth here, and the *byte layout* is pinned by a cross-language golden round-trip test.

## Files

| File | Role |
|------|------|
| `protocol.json` | **The authority** for versions, movement/action bitmask positions, slot-group opcodes, and inventory-op opcodes. |
| `gen_constants.py` | Generates `Protocol.java` and `_protocol.py` from `protocol.json`. Stdlib only. |
| `golden/cases.json` | **The authority** for the canonical test fixtures (human-readable field values). |
| `golden/*.bin` | The bytes `BinaryCodec` (the reference encoder) produces from each fixture. |

Generated, **do not hand-edit** (regenerate instead):

- `src/main/java/mod/kelvinlby/link/generated/Protocol.java`
- `pylib/src/ocl/_protocol.py`

The codecs, `SlotGroup`, and `InventoryAction.Op` all reference the generated constants, so a
constant can only be defined in `protocol.json`.

## How drift is caught

CI (`.github/workflows/build.yml`) fails the build if either authority and its derivatives
disagree:

1. `python protocol/gen_constants.py && git diff --exit-code` — the committed generated
   constants must match `protocol.json`.
2. `./gradlew build` runs `BinaryCodecGoldenTest` — Java encodes each fixture and asserts the
   bytes equal the committed `.bin`, and decodes each OCLI golden back to its fixture values.
3. `./gradlew genGolden && git diff --exit-code -- protocol/golden` — the committed `.bin`
   files must match what `BinaryCodec` currently encodes.
4. `python -m unittest discover -s pylib/tests` — Python decodes every `.bin` and re-encodes
   the OCLI fixtures, asserting agreement with the same goldens.

Together these close the loop: Java-produced goldens are decoded by Python, and Python-encoded
OCLI is asserted against the same goldens, so an offset/opcode/version change on **either**
side turns a build red.

## Changing the protocol (the ritual)

1. **Edit `protocol/protocol.json`** — bump `version` and/or `vis_version`, add/adjust opcodes.
2. **Update the hand-written layout** in *both* codecs (`BinaryCodec.java` and
   `pylib/src/ocl/__init__.py`) for the new/changed field. This is the only part not generated.
3. **Regenerate constants:** `python protocol/gen_constants.py`. Commit the regenerated
   `Protocol.java` and `_protocol.py`.
4. **Add/adjust a fixture** in `golden/cases.json` that exercises the change, then regenerate
   the bytes: `./gradlew genGolden`. Commit the updated `golden/*.bin`.
5. **Run both test suites locally** (`./gradlew test` and
   `python -m unittest discover -s pylib/tests`) before pushing; CI enforces the same.

If step 2 is forgotten on one side, the golden test goes red. If step 3 is forgotten, the
`git diff --exit-code` check goes red. Either way the desync surfaces at build time, not at
runtime as garbled bytes.
