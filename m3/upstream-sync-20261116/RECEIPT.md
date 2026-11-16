# M3 upstream synchronization receipt: jackson-databind 3.x

## Scope and preconditions
- Fork: `hsoliwal/jackson-databind`; protected target: `3.x`.
- Fork base head (pinned): `6860f3d270c97e856f3cae8faf093cc6ff4fb023`.
- Common ancestor: `e47547ce754ca3f2bb2d588e72f93ead778038e6`.
- Donor upstream commit: `FasterXML/jackson-databind@b63b8749041d31159c24d46018559bdcc23dc3a9` (Apache-2.0 repository).
- The donor fixes #6299/#6454: filtered single-element containers must not be unwrapped to invalid JSON.
- The fork was 7 commits ahead and 1 behind; no existing open PR was found at inspection.

## File-by-file immutable merge preconditions
All **11** existing donor-modified paths have exactly identical Git blob SHA-1 and file modes (`100644`) on fork head and common ancestor. The donor's new regression test was absent from both. Therefore the donor blobs can be overlaid without overwriting a fork-specific edit on these paths. Other fork files remain unmodified by the overlay.

| File | Fork/common ancestor blob | Donor blob |
|---|---|---|
| `release-notes/CREDITS` | `7709fc3d7fc002476b1733662a3f1fb2cf81a1b5` | `601593105b6c2212f5bab962d3011619740731d6` |
| `release-notes/VERSION` | `218859eacd19cdc495f2d8602bda5d49552731cf` | `7333362461522a45a9a57285c047db686c21c546` |
| `src/main/java/tools/jackson/databind/ser/jdk/CollectionSerializer.java` | `4b09c4ed5ef2ae8c47fb8f91ad75c4acfaaf51d4` | `bcc6946ae39a0ec2375ec236af8782b1ba1ec037` |
| `src/main/java/tools/jackson/databind/ser/jdk/EnumSetSerializer.java` | `43827f22f6384e3b7a5284e46212ac6c56109034` | `7a786c2dd0548f870bc5ca352791543c9bc03843` |
| `src/main/java/tools/jackson/databind/ser/jdk/IndexedListSerializer.java` | `a92a9ff7391b74d47d463fd0c52d5f1d8b7770a5` | `5a4c0d1b957254f90bb34b719f27f55e3d8e8f8c` |
| `src/main/java/tools/jackson/databind/ser/jdk/IndexedStringListSerializer.java` | `1e7b4ded6f9debd7047b5bd4ca6bd51f78031349` | `a7a55b253dfdf1779bdfce23551aacbf7d79eed8` |
| `src/main/java/tools/jackson/databind/ser/jdk/IterableSerializer.java` | `28f626a6b7c4fb35c3953250370c97b0178a4e36` | `13475db4dcb38cee3c91612b94302311470585a7` |
| `src/main/java/tools/jackson/databind/ser/jdk/JDKArraySerializers.java` | `17ec9dac051130570b097bcf5f55bc8f1a6e8f8c` | `5dd4df2ef5284520f904fb4501cd27ee2167cb5c` |
| `src/main/java/tools/jackson/databind/ser/jdk/ObjectArraySerializer.java` | `3d02dcde37f8de2a8ee2d4d457f63673416fbc34` | `61a8392a70dc51c079e58eed8903c8e59eb136a2` |
| `src/main/java/tools/jackson/databind/ser/jdk/StringArraySerializer.java` | `259e25fb5e8bbb59d6416d0122e9ae84d4254874` | `5267d954e940005e8a024b75c3a1ecf927b0ba30` |
| `src/main/java/tools/jackson/databind/ser/jdk/StringCollectionSerializer.java` | `146cc1e8d955f7bb26803910108eafc905a0a6a7` | `af4532302de48afc2ef74d7ead0352f2400d7ae5` |
| `src/test/java/tools/jackson/databind/ser/filter/JsonIncludeUnwrapSingle6299Test.java` | **absent** | `e6df50ed5633a36f2d58cedf9981c68adc84f687` |

## Planned operation and verification contract
The implementation merge commit must have two parents (this receipt's branch head and the exact donor upstream commit), with a tree derived from the receipt branch tree and containing only the 12 donor file changes. Do not rewrite or move the fork's `3.x` branch. Publish a **draft PR** for review, and never auto-merge.

Patch verification order: diff -> lint -> compile -> targeted regression -> full test suite -> runtime -> review. Evidence and logs are required for any passing gate. **No build, lint, unit or runtime verification has yet been performed by this audit.** The draft PR must remain unmerged until those checks pass.

This receipt records the source hashes and conflict analysis, not proof of behavior or correctness.
